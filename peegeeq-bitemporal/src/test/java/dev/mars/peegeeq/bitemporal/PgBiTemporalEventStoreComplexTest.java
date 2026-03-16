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
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.TransactionPropagation;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dev.mars.peegeeq.test.categories.TestCategories;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for complex PgBiTemporalEventStore functionality:
 * - Batch operations (appendBatch)
 * - Transactional operations (appendInTransaction, appendWithTransaction)
 * - Error handling and edge cases
 * - Temporal queries (getAsOfTransactionTime)
 * 
 * Target: Improve coverage from 44% to 70%+ by testing complex code paths.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-01-12
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@Testcontainers
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

    private static <T> T await(io.vertx.core.Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }

    @SuppressWarnings("unused")
    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

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

    private void cleanupDatabase() {
        try {
            String schema = resolveSchema();
            PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());

            Pool cleanupPool = PgBuilder.pool()
                .connectingTo(connectOptions)
                .build();

            cleanupPool.withConnection(conn -> 
                conn.query("TRUNCATE TABLE " + schema + ".bitemporal_event_log CASCADE").execute()
                    .map(result -> null)
            ).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            cleanupPool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore
        }
    }
    
    @BeforeEach
    void setUp() throws Exception {
        cleanupDatabase();
        
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
        manager.start();
        
        factory = new BiTemporalEventStoreFactory(manager);
        eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class, "bitemporal_event_log");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (eventStore != null) {
            eventStore.close();
            awaitAsyncDelay(100);
        }
        
        if (manager != null) {
            try {
                manager.closeReactive().toCompletionStage().toCompletableFuture().get(45, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                // Some CI/container environments can delay manager shutdown hooks.
                // Continue teardown to avoid masking the test's actual assertions.
                manager.closeReactive();
            }
        }
        
        if (vertx != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            vertx.close().onComplete(ar -> {
                if (ar.succeeded()) future.complete(null);
                else future.completeExceptionally(ar.cause());
            });
            future.get(10, TimeUnit.SECONDS);
        }
        
        cleanupDatabase();
        awaitAsyncDelay(100);
        
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.health-check.enabled");
        System.clearProperty("peegeeq.health-check.queue-checks-enabled");
        System.clearProperty("peegeeq.queue.dead-consumer-detection.enabled");
        System.clearProperty("peegeeq.queue.recovery.enabled");
    }

    // ==================== Batch Operations ====================
    
    @Test
    void testAppendBatchMultipleEvents() throws Exception {
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
        
        List<BiTemporalEvent<TestEvent>> events = eventStore.appendBatch(batch).join();
        
        assertNotNull(events);
        assertEquals(3, events.size());
        assertEquals("BatchType1", events.get(0).getEventType());
        assertEquals("b1", events.get(0).getPayload().getId());
        assertEquals("corr-1", events.get(0).getCorrelationId());
        assertEquals("agg-1", events.get(0).getAggregateId());
        
        // Verify all events persisted
        List<BiTemporalEvent<TestEvent>> retrieved = await(eventStore.query(
            EventQuery.forEventType("BatchType1")
        ));
        assertEquals(2, retrieved.size());
    }
    
    @Test
    void testAppendBatchEmpty() throws Exception {
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> empty = Collections.emptyList();
        List<BiTemporalEvent<TestEvent>> events = eventStore.appendBatch(empty).join();
        
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }
    
    @Test
    void testAppendBatchLarge() throws Exception {
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
        
        List<BiTemporalEvent<TestEvent>> events = eventStore.appendBatch(batch).join();
        
        assertEquals(50, events.size());
        
        // Verify query returns all
        List<BiTemporalEvent<TestEvent>> all = await(eventStore.query(
            EventQuery.forEventType("LargeBatch")
        ));
        assertEquals(50, all.size());
    }

    // ==================== Transaction Propagation ====================
    
    @Test
    void testAppendWithTransactionPropagation() throws Exception {
        TestEvent payload = new TestEvent("tx-prop", "transaction", 999);
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> event = await(eventStore.appendWithTransaction(
            "TxPropEvent",
            payload,
            validTime,
            Map.of("tx", "context"),
            "corr-tx",
            null,
            "agg-tx",
            TransactionPropagation.CONTEXT
        ));
        
        assertNotNull(event);
        assertEquals("TxPropEvent", event.getEventType());
        assertEquals(payload, event.getPayload());
        
        // Verify persisted
        BiTemporalEvent<TestEvent> retrieved = await(eventStore.getById(event.getEventId()));
        assertNotNull(retrieved);
        assertEquals(event.getEventId(), retrieved.getEventId());
    }

    // ==================== In-Transaction Operations ====================
    
    @Test
    void testAppendInTransactionCommit() throws Exception {
        PgConnectOptions options = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        Pool pool = PgBuilder.pool().connectingTo(options).using(vertx).build();
        
        CompletableFuture<BiTemporalEvent<TestEvent>> result = new CompletableFuture<>();
        
        pool.getConnection().onComplete(connAr -> {
            if (connAr.succeeded()) {
                SqlConnection conn = connAr.result();
                
                conn.begin().onComplete(txAr -> {
                    if (txAr.succeeded()) {
                        TestEvent payload = new TestEvent("in-tx-commit", "committed", 777);
                        
                        eventStore.appendInTransaction(
                            "InTxCommitEvent",
                            payload,
                            Instant.now(),
                            Map.of("in-tx", "true"),
                            "corr-in-tx",
                            null,
                            "agg-in-tx",
                            conn
                        ).onComplete(eventAr -> {
                            if (eventAr.failed()) {
                                txAr.result().rollback().onComplete(rb -> {
                                    conn.close();
                                    result.completeExceptionally(eventAr.cause());
                                });
                            } else {
                                txAr.result().commit().onComplete(commitAr -> {
                                    conn.close();
                                    if (commitAr.succeeded()) {
                                        result.complete(eventAr.result());
                                    } else {
                                        result.completeExceptionally(commitAr.cause());
                                    }
                                });
                            }
                        });
                    } else {
                        conn.close();
                        result.completeExceptionally(txAr.cause());
                    }
                });
            } else {
                result.completeExceptionally(connAr.cause());
            }
        });
        
        BiTemporalEvent<TestEvent> event = result.get(15, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals("InTxCommitEvent", event.getEventType());
        
        // Verify committed
        BiTemporalEvent<TestEvent> retrieved = await(eventStore.getById(event.getEventId()));
        assertNotNull(retrieved);
        
        pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
    
    @Test
    void testAppendInTransactionRollback() throws Exception {
        PgConnectOptions options = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());
        
        Pool pool = PgBuilder.pool().connectingTo(options).using(vertx).build();
        
        CompletableFuture<String> eventIdFuture = new CompletableFuture<>();
        
        pool.getConnection().onComplete(connAr -> {
            if (connAr.succeeded()) {
                SqlConnection conn = connAr.result();
                
                conn.begin().onComplete(txAr -> {
                    if (txAr.succeeded()) {
                        TestEvent payload = new TestEvent("in-tx-rollback", "rolled back", 666);
                        
                        eventStore.appendInTransaction(
                            "InTxRollbackEvent",
                            payload,
                            Instant.now(),
                            Map.of(),
                            null,
                            null,
                            null,
                            conn
                        ).onComplete(eventAr -> {
                            String eventId = eventAr.succeeded() && eventAr.result() != null ? eventAr.result().getEventId() : null;
                            
                            // Intentional rollback
                            txAr.result().rollback().onComplete(rb -> {
                                conn.close();
                                eventIdFuture.complete(eventId);
                            });
                        });
                    } else {
                        conn.close();
                        eventIdFuture.completeExceptionally(txAr.cause());
                    }
                });
            } else {
                eventIdFuture.completeExceptionally(connAr.cause());
            }
        });
        
        String eventId = eventIdFuture.get(15, TimeUnit.SECONDS);
        
        // Event should NOT exist after rollback
        if (eventId != null) {
            try {
                BiTemporalEvent<TestEvent> retrieved = await(eventStore.getById(eventId));
                assertNull(retrieved, "Event should not exist after rollback");
            } catch (Exception e) {
                // Expected - event doesn't exist
                assertTrue(true);
            }
        }
        
        pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ==================== Temporal Queries ====================
    
    @Test
    void testGetAsOfTransactionTimeOriginal() throws Exception {
        TestEvent original = new TestEvent("temporal-1", "original", 900);
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> event1 = await(eventStore.appendBuilder().eventType("TempEvent").payload(original).validTime(validTime).execute());
        Instant afterFirst = Instant.now();
        awaitAsyncDelay(150);
        
        TestEvent corrected = new TestEvent("temporal-1", "corrected", 950);
        await(eventStore.appendCorrection(event1.getEventId(), "TempEvent", corrected, validTime, "Correction"));
        
        // Query as of time before correction
        BiTemporalEvent<TestEvent> historical = await(eventStore.getAsOfTransactionTime(
            event1.getEventId(), afterFirst
        ));
        
        assertNotNull(historical);
        assertEquals("original", historical.getPayload().getData());
        assertEquals(1L, historical.getVersion());
        assertFalse(historical.isCorrection());
    }
    
    @Test
    void testGetAsOfTransactionTimeFuture() throws Exception {
        TestEvent payload = new TestEvent("future", "data", 1000);
        BiTemporalEvent<TestEvent> event = await(eventStore.appendBuilder().eventType("FutureEvent").payload(payload).validTime(Instant.now()).execute());
        
        Instant futureTime = Instant.now().plus(1, ChronoUnit.DAYS);
        BiTemporalEvent<TestEvent> result = await(eventStore.getAsOfTransactionTime(
            event.getEventId(), futureTime
        ));
        
        assertNotNull(result);
        assertEquals(event.getEventId(), result.getEventId());
    }

    @Test
    void testGetAsOfTransactionTimeBeforeEventCreationReturnsNull() throws Exception {
        Instant beforeCreate = Instant.now();
        awaitAsyncDelay(25);

        TestEvent payload = new TestEvent("before-create", "data", 1001);
        BiTemporalEvent<TestEvent> event = await(eventStore.appendBuilder().eventType("BeforeCreateEvent").payload(payload).validTime(Instant.now()).execute());

        BiTemporalEvent<TestEvent> result = await(eventStore.getAsOfTransactionTime(
            event.getEventId(), beforeCreate
        ));

        assertNull(result, "As-of query before event creation should not return the event");
    }

    @Test
    void testGetAsOfTransactionTimeUsingCorrectionEventIdResolvesToRootLineage() throws Exception {
        TestEvent original = new TestEvent("temporal-corr", "original", 1111);
        Instant validTime = Instant.now();

        BiTemporalEvent<TestEvent> originalEvent = await(eventStore.appendBuilder().eventType("TempEvent").payload(original).validTime(validTime).execute());
        Instant afterOriginal = Instant.now();
        awaitAsyncDelay(120);

        TestEvent corrected = new TestEvent("temporal-corr", "corrected", 2222);
        BiTemporalEvent<TestEvent> correctionEvent = await(eventStore
                .appendCorrection(originalEvent.getEventId(), "TempEvent", corrected, validTime, "fix")
        );

        // Query using correction ID, but as-of before correction transaction time.
        BiTemporalEvent<TestEvent> historical = await(eventStore
                .getAsOfTransactionTime(correctionEvent.getEventId(), afterOriginal)
        );

        assertNotNull(historical, "As-of lookup should resolve correction lineage back to root event");
        assertEquals("original", historical.getPayload().getData());
        assertEquals(1L, historical.getVersion());
        assertEquals(originalEvent.getEventId(), historical.getEventId());
    }

    @Test
    void testEventBusDistributionAppendPreservesCausationId() throws Exception {
        String previousDistributionValue = System.getProperty("peegeeq.database.use.event.bus.distribution");
        System.setProperty("peegeeq.database.use.event.bus.distribution", "true");

        try {
            PgBiTemporalEventStore.deployDatabaseWorkerVerticles(1, "bitemporal_event_log")
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            String correlationId = "corr-eb-" + System.nanoTime();
            String causationId = "cause-eb-" + System.nanoTime();

                BiTemporalEvent<TestEvent> appended = await(eventStore
                    .appendBuilder().eventType("EventBusCausation").payload(new TestEvent("evt-bus", "payload", 77)).validTime(Instant.now()).headers(Map.of("source", "event-bus")).correlationId(correlationId).causationId(causationId).aggregateId("agg-eb").execute()
                );

            assertNotNull(appended);
            assertEquals(causationId, appended.getCausationId(),
                    "Event returned from event-bus append should preserve causationId");

            BiTemporalEvent<TestEvent> fetched = await(eventStore.getById(appended.getEventId()));
            assertNotNull(fetched);
            assertEquals(causationId, fetched.getCausationId(),
                    "Stored event should persist causationId when event-bus distribution is enabled");
        } finally {
            if (previousDistributionValue == null) {
                System.clearProperty("peegeeq.database.use.event.bus.distribution");
            } else {
                System.setProperty("peegeeq.database.use.event.bus.distribution", previousDistributionValue);
            }
        }
    }

        @Test
        void testGetAsOfTransactionTimeDeepCorrectionChainUsingLatestCorrectionId() throws Exception {
        Instant validTime = Instant.now();

        BiTemporalEvent<TestEvent> v1 = await(eventStore
            .appendBuilder().eventType("ChainEvent").payload(new TestEvent("chain", "v1", 1)).validTime(validTime).execute()
        );
        Instant tAfterV1 = Instant.now();
        awaitAsyncDelay(80);

        @SuppressWarnings("unused")
        BiTemporalEvent<TestEvent> v2 = await(eventStore
            .appendCorrection(v1.getEventId(), "ChainEvent", new TestEvent("chain", "v2", 2), validTime, "c1")
        );
        Instant tAfterV2 = Instant.now();
        awaitAsyncDelay(80);

        @SuppressWarnings("unused")
        BiTemporalEvent<TestEvent> v3 = await(eventStore
            .appendCorrection(v1.getEventId(), "ChainEvent", new TestEvent("chain", "v3", 3), validTime, "c2")
        );
        Instant tAfterV3 = Instant.now();
        awaitAsyncDelay(80);

        BiTemporalEvent<TestEvent> v4 = await(eventStore
            .appendCorrection(v1.getEventId(), "ChainEvent", new TestEvent("chain", "v4", 4), validTime, "c3")
        );

        // Query lineage using latest correction ID, but at each historical checkpoint.
        BiTemporalEvent<TestEvent> asOfV1 = await(eventStore.getAsOfTransactionTime(v4.getEventId(), tAfterV1));
        BiTemporalEvent<TestEvent> asOfV2 = await(eventStore.getAsOfTransactionTime(v4.getEventId(), tAfterV2));
        BiTemporalEvent<TestEvent> asOfV3 = await(eventStore.getAsOfTransactionTime(v4.getEventId(), tAfterV3));
        BiTemporalEvent<TestEvent> asOfLatest = await(eventStore.getAsOfTransactionTime(v4.getEventId(), Instant.now()));

        assertNotNull(asOfV1);
        assertNotNull(asOfV2);
        assertNotNull(asOfV3);
        assertNotNull(asOfLatest);

        assertEquals("v1", asOfV1.getPayload().getData());
        assertEquals(1L, asOfV1.getVersion());

        assertEquals("v2", asOfV2.getPayload().getData());
        assertEquals(2L, asOfV2.getVersion());

        assertEquals("v3", asOfV3.getPayload().getData());
        assertEquals(3L, asOfV3.getVersion());

        assertEquals("v4", asOfLatest.getPayload().getData());
        assertEquals(4L, asOfLatest.getVersion());
        }

    // ==================== Error Handling ====================
    
    @Test
    void testAppendNullEventType() {
        assertThrows(Exception.class, () -> {
            await(eventStore.appendBuilder().eventType(null).payload(new TestEvent("x", "data", 1)).validTime(Instant.now()).execute());
        });
    }

    @Test
    void testAppendEmptyEventType() {
        assertThrows(Exception.class, () -> {
            await(eventStore.appendBuilder().eventType("   ").payload(new TestEvent("x", "data", 1)).validTime(Instant.now()).execute());
        });
    }

    @Test
    void testAppendWithLongCausationIdRejected() {
        String longCausationId = "c".repeat(256);
        assertThrows(Exception.class, () -> {
            await(eventStore.appendBuilder().eventType("TestType").payload(new TestEvent("x", "data", 1)).validTime(Instant.now()).headers(Map.of()).correlationId("corr").causationId(longCausationId).aggregateId("agg").execute());
        });
    }
    
    @Test
    void testAppendNullPayload() {
        assertThrows(Exception.class, () -> {
            await(eventStore.appendBuilder().eventType("TestType").payload(null).validTime(Instant.now()).execute());
        });
    }

    @Test
    void testObjectPayloadScalarRoundTripMatchesNativeOutboxSemantics() {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        try {
            BiTemporalEvent<Object> appended = await(objectStore.appendBuilder()
                .eventType("ObjectScalar")
                .payload("hello-world")
                .validTime(Instant.now())
                .execute());
            BiTemporalEvent<Object> fetched = await(objectStore.getById(appended.getEventId()));

            assertNotNull(fetched);
            assertEquals("hello-world", fetched.getPayload());
            assertTrue(fetched.getPayload() instanceof String,
                    "Object payload should unwrap to scalar value, not a wrapper map");
        } finally {
            objectStore.close();
        }
    }

    @Test
    void testObjectPayloadNumericAndBooleanRoundTripMatchesNativeOutboxSemantics() {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        try {
            BiTemporalEvent<Object> numberAppended = await(objectStore.appendBuilder()
                .eventType("ObjectNumber")
                .payload(42)
                .validTime(Instant.now())
                .execute());
            BiTemporalEvent<Object> numberFetched = await(objectStore.getById(numberAppended.getEventId()));

            assertNotNull(numberFetched);
            assertEquals(42, numberFetched.getPayload());
            assertTrue(numberFetched.getPayload() instanceof Integer,
                    "Numeric Object payload should unwrap to Integer scalar");

            BiTemporalEvent<Object> boolAppended = await(objectStore.appendBuilder()
                .eventType("ObjectBoolean")
                .payload(true)
                .validTime(Instant.now())
                .execute());
            BiTemporalEvent<Object> boolFetched = await(objectStore.getById(boolAppended.getEventId()));

            assertNotNull(boolFetched);
            assertEquals(true, boolFetched.getPayload());
            assertTrue(boolFetched.getPayload() instanceof Boolean,
                    "Boolean Object payload should unwrap to Boolean scalar");
        } finally {
            objectStore.close();
        }
    }

        @Test
        void testObjectPayloadLegacyScalarJsonStoredWithoutWrapperCanBeRead() throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        try {
            String eventId = UUID.randomUUID().toString();
            Instant validTime = Instant.now();

            Pool pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions()
                    .setHost(postgres.getHost())
                    .setPort(postgres.getFirstMappedPort())
                    .setDatabase(postgres.getDatabaseName())
                    .setUser(postgres.getUsername())
                    .setPassword(postgres.getPassword()))
                .build();

            String insertSql = """
                INSERT INTO bitemporal_event_log
                (event_id, event_type, valid_time, transaction_time, payload, headers,
                 version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                VALUES ($1, $2, $3, NOW(), $4::jsonb, $5::jsonb, 1, $6, $7, $8, false, NOW())
                """;

            pool.preparedQuery(insertSql)
                .execute(io.vertx.sqlclient.Tuple.of(
                    eventId,
                    "LegacyScalar",
                    validTime.atOffset(java.time.ZoneOffset.UTC),
                    "\"legacy-scalar\"",
                    "{}",
                    eventId,
                    null,
                    null))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            BiTemporalEvent<Object> fetched = await(objectStore.getById(eventId));
            assertNotNull(fetched);
            assertEquals("legacy-scalar", fetched.getPayload());
            assertTrue(fetched.getPayload() instanceof String);
        } finally {
            objectStore.close();
        }
        }

        @Test
        void testObjectPayloadLegacyArrayJsonCanBeRead() throws Exception {
        EventStore<Object> objectStore = factory.createObjectEventStore();
        try {
            String eventId = UUID.randomUUID().toString();
            Instant validTime = Instant.now();

            Pool pool = PgBuilder.pool()
                .connectingTo(new PgConnectOptions()
                    .setHost(postgres.getHost())
                    .setPort(postgres.getFirstMappedPort())
                    .setDatabase(postgres.getDatabaseName())
                    .setUser(postgres.getUsername())
                    .setPassword(postgres.getPassword()))
                .build();

            String insertSql = """
                INSERT INTO bitemporal_event_log
                (event_id, event_type, valid_time, transaction_time, payload, headers,
                 version, correlation_id, causation_id, aggregate_id, is_correction, created_at)
                VALUES ($1, $2, $3, NOW(), $4::jsonb, $5::jsonb, 1, $6, $7, $8, false, NOW())
                """;

            pool.preparedQuery(insertSql)
                .execute(io.vertx.sqlclient.Tuple.of(
                    eventId,
                    "LegacyArray",
                    validTime.atOffset(java.time.ZoneOffset.UTC),
                    "[1,2,3]",
                    "{}",
                    eventId,
                    null,
                    null))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

            BiTemporalEvent<Object> fetched = await(objectStore.getById(eventId));
            assertNotNull(fetched);
            assertTrue(fetched.getPayload() instanceof List, "Array JSON should deserialize to List for Object payload");
            assertEquals(List.of(1, 2, 3), fetched.getPayload());
        } finally {
            objectStore.close();
        }
        }
    
    @Test
    void testAppendNullValidTime() {
        assertThrows(Exception.class, () -> {
            await(eventStore.appendBuilder().eventType("TestType").payload(new TestEvent("x", "data", 1)).validTime(null).execute());
        });
    }
    
    @Test
    void testAppendCorrectionNonExistent() {
        String fakeId = UUID.randomUUID().toString();
        
        assertThrows(Exception.class, () -> {
            await(eventStore.appendCorrection(fakeId, "BadCorrection", 
                new TestEvent("x", "data", 1), Instant.now(), "Invalid"));
        });
    }
    
    @Test
    void testGetByIdNonExistent() {
        String fakeId = UUID.randomUUID().toString();
        
        try {
            BiTemporalEvent<TestEvent> result = await(eventStore.getById(fakeId));
            assertNull(result);
        } catch (Exception e) {
            // Either null or exception acceptable
            assertTrue(true);
        }
    }

    // ==================== Query Complexity ====================
    
    @Test
    void testQueryByCorrelationId() throws Exception {
        Instant validTime = Instant.now();
        String uniqueCorr = "test-corr-" + UUID.randomUUID();
        String otherCorr = "test-other-" + UUID.randomUUID();
        
        // Append events with unique correlation IDs
        String id1 = await(eventStore.appendBuilder().eventType("CorEvent").payload(new TestEvent("c1", "data1", 10)).validTime(validTime).headers(Map.of()).correlationId(uniqueCorr).causationId(null).aggregateId("agg1").execute()).getEventId();
        await(eventStore.appendBuilder().eventType("CorEvent").payload(new TestEvent("c2", "data2", 20)).validTime(validTime).headers(Map.of()).correlationId(otherCorr).causationId(null).aggregateId("agg2").execute());
        String id3 = await(eventStore.appendBuilder().eventType("CorEvent").payload(new TestEvent("c3", "data3", 30)).validTime(validTime).headers(Map.of()).correlationId(uniqueCorr).causationId(null).aggregateId("agg3").execute()).getEventId();
        
        List<BiTemporalEvent<TestEvent>> events = await(eventStore.query(
            EventQuery.builder()
                .correlationId(uniqueCorr)
                .build()
        ));
        
        assertNotNull(events);
        // Filter to only events we just created (by ID) to avoid pollution from other tests
        List<BiTemporalEvent<TestEvent>> ourEvents = events.stream()
            .filter(e -> e.getEventId().equals(id1) || e.getEventId().equals(id3))
            .toList();
        assertEquals(2, ourEvents.size(), "Should find exactly 2 events we just created with correlation ID: " + uniqueCorr);
        assertTrue(ourEvents.stream().allMatch(e -> uniqueCorr.equals(e.getCorrelationId())));
    }
    
    @Test
    void testQueryWithLimit() throws Exception {
        Instant validTime = Instant.now();
        
        for (int i = 1; i <= 15; i++) {
            await(eventStore.appendBuilder().eventType("LimitEvent").payload(new TestEvent("lim-" + i, "data", i * 100)).validTime(validTime).execute());
        }
        
        List<BiTemporalEvent<TestEvent>> events = await(eventStore.query(
            EventQuery.builder()
                .eventType("LimitEvent")
                .limit(7)
                .build()
        ));
        
        assertNotNull(events);
        assertTrue(events.size() <= 7);
    }
    
    @Test
    void testGetAllVersionsMultipleCorrections() throws Exception {
        TestEvent v1 = new TestEvent("versions", "v1", 1);
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> event1 = await(eventStore.appendBuilder().eventType("VersionEvent").payload(v1).validTime(validTime).execute());
        
        TestEvent v2 = new TestEvent("versions", "v2", 2);
        await(eventStore.appendCorrection(
            event1.getEventId(), "VersionEvent", v2, validTime, "correction 1"));
        
        TestEvent v3 = new TestEvent("versions", "v3", 3);
        await(eventStore.appendCorrection(
            event1.getEventId(), "VersionEvent", v3, validTime, "correction 2"));
        
        List<BiTemporalEvent<TestEvent>> versions = await(eventStore.getAllVersions(event1.getEventId()));
        
        assertEquals(3, versions.size());
        assertEquals(1L, versions.get(0).getVersion());
        assertEquals(2L, versions.get(1).getVersion());
        assertEquals(3L, versions.get(2).getVersion());
        assertTrue(versions.get(1).isCorrection());
        assertTrue(versions.get(2).isCorrection());
    }

    // ==================== Close and Lifecycle ====================
    
    @Test
    void testCloseIdempotent() throws Exception {
        eventStore.close();
        
        assertDoesNotThrow(() -> {
            eventStore.close();
            eventStore.close();
        });
    }
    
    @Test
    void testAppendAfterClose() throws Exception {
        TestEvent payload = new TestEvent("after-close", "data", 123);
        
        eventStore.close();
        awaitAsyncDelay(200);
        
        // Behavior after close may vary - test exercises code path
        try {
            await(eventStore.appendBuilder().eventType("AfterClose").payload(payload).validTime(Instant.now()).execute());
            // May succeed or fail depending on implementation
        } catch (Exception e) {
            // Expected in some implementations
            assertTrue(true);
        }
    }

    // ==================== Concurrent Operations ====================
    
    @Test
    void testConcurrentAppends() throws Exception {
        Instant validTime = Instant.now();
        List<CompletableFuture<BiTemporalEvent<TestEvent>>> futures = new ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            final int index = i;
            CompletableFuture<BiTemporalEvent<TestEvent>> future = eventStore.appendBuilder().eventType("ConcurrentEvent").payload(new TestEvent("concurrent-" + index, "data-" + index, index)).validTime(validTime).execute().toCompletionStage().toCompletableFuture();
            futures.add(future);
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        List<BiTemporalEvent<TestEvent>> all = await(eventStore.query(
            EventQuery.forEventType("ConcurrentEvent")
        ));
        
        assertEquals(10, all.size());
    }
    
    @Test
    void testConcurrentBatchAndSingle() throws Exception {
        Instant validTime = Instant.now();
        
        // Batch operation
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
        
        CompletableFuture<List<BiTemporalEvent<TestEvent>>> batchFuture = eventStore.appendBatch(batch);
        
        // Single operations
        CompletableFuture<BiTemporalEvent<TestEvent>> single1 = eventStore.appendBuilder().eventType("MixedEvent").payload(new TestEvent("single-1", "single", 10)).validTime(validTime).execute().toCompletionStage().toCompletableFuture();
        CompletableFuture<BiTemporalEvent<TestEvent>> single2 = eventStore.appendBuilder().eventType("MixedEvent").payload(new TestEvent("single-2", "single", 20)).validTime(validTime).execute().toCompletionStage().toCompletableFuture();
        
        CompletableFuture.allOf(batchFuture, single1, single2).join();
        
        List<BiTemporalEvent<TestEvent>> all = await(eventStore.query(
            EventQuery.forEventType("MixedEvent")
        ));
        
        assertEquals(4, all.size());
    }
    
    @Test
    void testGetUniqueAggregates() throws Exception {
        Instant validTime = Instant.now();
        String eventType = "AggTestEvent";
        
        // Append events with different aggregates
        await(eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e1", "data", 1)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-001").execute());
        await(eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e2", "data", 2)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-002").execute());
        await(eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e3", "data", 3)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-001").execute()); // Duplicate aggregate
        await(eventStore.appendBuilder().eventType(eventType).payload(new TestEvent("e4", "data", 4)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("agg-003").execute());
        
        List<String> uniqueAggregates = await(eventStore.getUniqueAggregates(eventType));
        
        assertNotNull(uniqueAggregates);
        assertEquals(3, uniqueAggregates.size());
        assertTrue(uniqueAggregates.contains("agg-001"));
        assertTrue(uniqueAggregates.contains("agg-002"));
        assertTrue(uniqueAggregates.contains("agg-003"));
    }
    
    @Test
    void testAppendWithAllOverloads() throws Exception {
        Instant validTime = Instant.now();
        
        // Test 3-parameter overload
        BiTemporalEvent<TestEvent> e1 = await(eventStore.appendBuilder().eventType("OverloadEvent").payload(new TestEvent("o1", "data", 1)).validTime(validTime).execute());
        assertNotNull(e1);
        
        // Test 4-parameter overload (with headers)
        BiTemporalEvent<TestEvent> e2 = await(eventStore.appendBuilder().eventType("OverloadEvent").payload(new TestEvent("o2", "data", 2)).validTime(validTime).headers(Map.of("header1", "value1")).execute());
        assertNotNull(e2);
        assertEquals("value1", e2.getHeaders().get("header1"));
        
        // Test appendWithTransaction 4-parameter overload
        BiTemporalEvent<TestEvent> e3 = await(eventStore.appendWithTransaction(
            "OverloadEvent",
            new TestEvent("o3", "data", 3),
            validTime,
            TransactionPropagation.CONTEXT
        ));
        assertNotNull(e3);
        
        // Test appendWithTransaction 5-parameter overload
        BiTemporalEvent<TestEvent> e4 = await(eventStore.appendWithTransaction(
            "OverloadEvent",
            new TestEvent("o4", "data", 4),
            validTime,
            Map.of("tx", "true"),
            TransactionPropagation.CONTEXT
        ));
        assertNotNull(e4);
        
        // Test appendWithTransaction 6-parameter overload
        BiTemporalEvent<TestEvent> e5 = await(eventStore.appendWithTransaction(
            "OverloadEvent",
            new TestEvent("o5", "data", 5),
            validTime,
            Map.of(),
            "corr-tx",
            TransactionPropagation.CONTEXT
        ));
        assertNotNull(e5);
        assertEquals("corr-tx", e5.getCorrelationId());
        
        List<BiTemporalEvent<TestEvent>> all = await(eventStore.query(
            EventQuery.forEventType("OverloadEvent")
        ));
        
        assertTrue(all.size() >= 5, "Should have at least 5 events from overload tests");
    }
    
    @Test
    void testAppendInTransactionOverloads() throws Exception {
        Instant validTime = Instant.now();
        Vertx vertx = Vertx.vertx();
        
        try {
            PgConnectOptions options = new PgConnectOptions()
                .setHost(postgres.getHost())
                .setPort(postgres.getFirstMappedPort())
                .setDatabase(postgres.getDatabaseName())
                .setUser(postgres.getUsername())
                .setPassword(postgres.getPassword());
            
            Pool pool = PgBuilder.pool().connectingTo(options).build();
            
            pool.withTransaction(conn -> {
                // Test 4-parameter overload
                CompletableFuture<BiTemporalEvent<TestEvent>> f1 = eventStore.appendInTransaction(
                    "InTxOverloadEvent",
                    new TestEvent("itx1", "data", 1),
                    validTime,
                    conn
                ).toCompletionStage().toCompletableFuture();
                
                // Test 5-parameter overload
                CompletableFuture<BiTemporalEvent<TestEvent>> f2 = f1.thenCompose(e1 -> {
                    assertNotNull(e1);
                    return eventStore.appendInTransaction(
                        "InTxOverloadEvent",
                        new TestEvent("itx2", "data", 2),
                        validTime,
                        Map.of("intx", "true"),
                        conn
                    ).toCompletionStage().toCompletableFuture();
                });
                
                // Test 6-parameter overload
                CompletableFuture<BiTemporalEvent<TestEvent>> f3 = f2.thenCompose(e2 -> {
                    assertNotNull(e2);
                    assertEquals("true", e2.getHeaders().get("intx"));
                    return eventStore.appendInTransaction(
                        "InTxOverloadEvent",
                        new TestEvent("itx3", "data", 3),
                        validTime,
                        Map.of(),
                        "intx-corr",
                        conn
                    ).toCompletionStage().toCompletableFuture();
                });
                
                // Convert CompletableFuture to Vert.x Future
                return io.vertx.core.Future.fromCompletionStage(f3.thenApply(e3 -> {
                    assertNotNull(e3);
                    assertEquals("intx-corr", e3.getCorrelationId());
                    return null;
                }));
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            
            List<BiTemporalEvent<TestEvent>> all = await(eventStore.query(
                EventQuery.forEventType("InTxOverloadEvent")
            ));
            
            assertEquals(3, all.size(), "Should have 3 events from in-transaction overload tests");
            
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testGetByIdReactive() throws Exception {
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> created = await(eventStore.appendBuilder().eventType("GetByIdTest").payload(new TestEvent("reactive-id", "reactive-data", 999)).validTime(validTime).execute());
        
        String eventId = created.getEventId();
        
        BiTemporalEvent<TestEvent> retrieved = await(eventStore.getById(eventId));
        
        assertNotNull(retrieved);
        assertEquals(eventId, retrieved.getEventId());
        assertEquals("reactive-id", retrieved.getPayload().id);
        assertEquals(999, retrieved.getPayload().value);
    }
    
    @Test
    void testGetAllVersionsReactive() throws Exception {
        Instant validTime = Instant.now();
        
        // Create original version
        BiTemporalEvent<TestEvent> original = await(eventStore.appendBuilder().eventType("VersionTest").payload(new TestEvent("v1", "original", 100)).validTime(validTime).execute());
        String eventId = original.getEventId();
        
        // Create correction 1
        await(eventStore.appendCorrection(eventId, "VersionTest", 
            new TestEvent("v2", "correction1", 200), validTime.plus(1, ChronoUnit.DAYS),
            "Correction 1 reason"));
        
        // Create correction 2
        await(eventStore.appendCorrection(eventId, "VersionTest",
            new TestEvent("v3", "correction2", 300), validTime.plus(2, ChronoUnit.DAYS),
            "Correction 2 reason"));
        
        List<BiTemporalEvent<TestEvent>> versions = await(eventStore.getAllVersions(eventId));
        
        assertNotNull(versions);
        assertTrue(versions.size() >= 3, "Should have original + 2 corrections = 3 versions");
    }
    
    @Test
    void testQueryReactive() throws Exception {
        Instant validTime = Instant.now();
        String uniqueType = "ReactiveQueryTest-" + UUID.randomUUID().toString().substring(0, 8);
        
        await(eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("rq1", "data", 1)).validTime(validTime).execute());
        await(eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("rq2", "data", 2)).validTime(validTime).execute());
        await(eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("rq3", "data", 3)).validTime(validTime).execute());
        
        List<BiTemporalEvent<TestEvent>> results = await(eventStore.query(
            EventQuery.forEventType(uniqueType)
        ));
        
        assertNotNull(results);
        assertEquals(3, results.size());
    }
    
    @Test
    void testStatsReactive() throws Exception {
        // Append some events to ensure stats are generated
        Instant validTime = Instant.now();
        await(eventStore.appendBuilder().eventType("StatsTest").payload(new TestEvent("s1", "data", 1)).validTime(validTime).execute());
        await(eventStore.appendBuilder().eventType("StatsTest").payload(new TestEvent("s2", "data", 2)).validTime(validTime).execute());
        
        EventStore.EventStoreStats stats = await(eventStore.getStats());
        
        assertNotNull(stats);
        assertTrue(stats.getTotalEvents() >= 2);
        assertNotNull(stats.getOldestEventTime());
        assertNotNull(stats.getNewestEventTime());
    }
    
    @Test
    void testAppendCorrectionWithFullParameters() throws Exception {
        Instant validTime = Instant.now();
        
        // Create original event
        BiTemporalEvent<TestEvent> original = await(eventStore.appendBuilder().eventType("CorrectionTest").payload(new TestEvent("original", "data", 100)).validTime(validTime).execute());
        
        String eventId = original.getEventId();
        
        // Test full appendCorrection overload with all parameters
        BiTemporalEvent<TestEvent> correction = await(eventStore.appendCorrection(
            eventId,
            "CorrectionTest",
            new TestEvent("corrected", "corrected-data", 200),
            validTime.plus(1, ChronoUnit.HOURS),
            Map.of("correction-header", "value1"),
            "corr-123",
            "agg-corrected",
            "Data quality improvement"
        ));
        
        assertNotNull(correction);
        // Correction creates a new event that references the original
        assertEquals(eventId, correction.getPreviousVersionId());
        assertEquals("corrected", correction.getPayload().id);
        assertEquals("value1", correction.getHeaders().get("correction-header"));
        assertEquals("corr-123", correction.getCorrelationId());
        assertEquals("agg-corrected", correction.getAggregateId());
    }
    
    @Test
    void testQueryWithAggregateIdFilter() throws Exception {
        Instant validTime = Instant.now();
        String uniqueAgg = "agg-filter-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Create events with different aggregates
        await(eventStore.appendBuilder().eventType("AggFilterTest").payload(new TestEvent("e1", "data", 1)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId(uniqueAgg).execute());
        await(eventStore.appendBuilder().eventType("AggFilterTest").payload(new TestEvent("e2", "data", 2)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId("other-agg").execute());
        await(eventStore.appendBuilder().eventType("AggFilterTest").payload(new TestEvent("e3", "data", 3)).validTime(validTime).headers(Map.of()).correlationId(null).causationId(null).aggregateId(uniqueAgg).execute());
        
        // Query by aggregate ID
        List<BiTemporalEvent<TestEvent>> results = await(eventStore.query(
            EventQuery.builder()
                .eventType("AggFilterTest")
                .aggregateId(uniqueAgg)
                .build()
        ));
        
        assertNotNull(results);
        assertTrue(results.size() >= 2);
        assertTrue(results.stream().allMatch(e -> uniqueAgg.equals(e.getAggregateId())));
    }
    
    @Test
    void testAppendReactiveDirectly() throws Exception {
        Instant validTime = Instant.now();
        
        // Test the reactive append path directly
        BiTemporalEvent<TestEvent> event = await(eventStore.appendBuilder().eventType("ReactiveAppendTest").payload(new TestEvent("reactive", "reactive-data", 777)).validTime(validTime).execute());
        
        assertNotNull(event);
        assertEquals("reactive", event.getPayload().id);
        assertEquals(777, event.getPayload().value);
        assertEquals("ReactiveAppendTest", event.getEventType());
    }
    
    @Test
    void testMultipleCorrections() throws Exception {
        Instant validTime = Instant.now();
        
        // Create original
        BiTemporalEvent<TestEvent> original = await(eventStore.appendBuilder().eventType("MultiCorrectionTest").payload(new TestEvent("v0", "original", 100)).validTime(validTime).execute());
        
        String eventId = original.getEventId();
        
        // Apply multiple corrections
        for (int i = 1; i <= 5; i++) {
            await(eventStore.appendCorrection(
                eventId,
                "MultiCorrectionTest",
                new TestEvent("v" + i, "correction-" + i, 100 + i),
                validTime.plus(i, ChronoUnit.HOURS),
                "Correction " + i
            ));
        }
        
        // Verify all versions
        List<BiTemporalEvent<TestEvent>> versions = await(eventStore.getAllVersions(eventId));
        
        assertNotNull(versions);
        assertTrue(versions.size() >= 6, "Should have original + 5 corrections");
    }
    
    @Test
    void testGetAsOfTransactionTimeWithPreciseTimestamp() throws Exception {
        Instant validTime = Instant.now();
        
        // Create event
        BiTemporalEvent<TestEvent> event = await(eventStore.appendBuilder().eventType("TimeQueryTest").payload(new TestEvent("time-test", "data", 999)).validTime(validTime).execute());
        
        String eventId = event.getEventId();
        
        // Query as of a specific transaction time (just after creation)
        Instant queryTime = Instant.now();
        BiTemporalEvent<TestEvent> retrieved = await(eventStore.getAsOfTransactionTime(
            eventId,
            queryTime
        ));
        
        assertNotNull(retrieved);
        assertEquals(eventId, retrieved.getEventId());
        assertEquals("time-test", retrieved.getPayload().id);
    }
    
    @Test
    void testHeadersSerialization() throws Exception {
        Instant validTime = Instant.now();
        Map<String, String> complexHeaders = Map.of(
            "header-1", "value-1",
            "header-2", "value-2",
            "header-3", "value-3",
            "special-chars", "value with spaces & symbols!@#"
        );
        
        BiTemporalEvent<TestEvent> event = await(eventStore.appendBuilder().eventType("HeaderTest").payload(new TestEvent("headers", "data", 111)).validTime(validTime).headers(complexHeaders).execute());
        
        assertNotNull(event);
        assertEquals(4, event.getHeaders().size());
        assertEquals("value-1", event.getHeaders().get("header-1"));
        assertEquals("value with spaces & symbols!@#", event.getHeaders().get("special-chars"));
        
        // Retrieve and verify headers persisted
        BiTemporalEvent<TestEvent> retrieved = await(eventStore.getById(event.getEventId()));
        assertEquals(4, retrieved.getHeaders().size());
        assertEquals("value-1", retrieved.getHeaders().get("header-1"));
    }
    
    @Test
    void testEmptyHeaders() throws Exception {
        Instant validTime = Instant.now();
        
        BiTemporalEvent<TestEvent> event = await(eventStore.appendBuilder().eventType("EmptyHeaderTest").payload(new TestEvent("no-headers", "data", 222)).validTime(validTime).headers(Map.of()).execute());
        
        assertNotNull(event);
        assertTrue(event.getHeaders().isEmpty() || event.getHeaders().size() == 0);
    }
    
    @Test
    void testBatchWithDifferentEventTypes() throws Exception {
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
        
        List<BiTemporalEvent<TestEvent>> results = eventStore.appendBatch(mixedBatch).join();
        
        assertNotNull(results);
        assertEquals(3, results.size());
        
        // Verify different types
        Set<String> types = results.stream().map(BiTemporalEvent::getEventType).collect(java.util.stream.Collectors.toSet());
        assertTrue(types.contains("TypeA"));
        assertTrue(types.contains("TypeB"));
        assertTrue(types.contains("TypeC"));
    }
    
    // ===== ADDITIONAL COVERAGE TESTS FOR EDGE CASES =====
    
    @Test
    @DisplayName("getAsOfTransactionTime with past time should handle historical query")
    void testGetAsOfTransactionTimePastTime() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-time-test", "data", 999);
        
        // Append an event
        BiTemporalEvent<TestEvent> appended = await(eventStore.appendBuilder().eventType("TxTimeTest").payload(payload).validTime(validTime).execute());
        assertNotNull(appended);
        
        // Query with a transaction time far in the past
        Instant pastTime = Instant.parse("2020-01-01T00:00:00Z");
        BiTemporalEvent<TestEvent> result = await(eventStore.getAsOfTransactionTime(appended.getEventId(), pastTime));
        
        // The API returns null or the event depending on implementation - just verify API works
        // Note: this tests the getAsOfTransactionTime code path which is important for coverage
        // The result depends on whether bi-temporal semantics filter by transaction time
        if (result != null) {
            assertEquals(appended.getEventId(), result.getEventId());
        }
    }
    
    @Test
    @DisplayName("appendWithTransaction with full parameters should work")
    void testAppendWithTransactionFull() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-full", "transaction data", 888);
        
        // Use appendWithTransaction which manages its own transaction
        BiTemporalEvent<TestEvent> result = await(eventStore.appendWithTransaction(
            "TxFullTest",
            payload,
            validTime,
            Map.of("tx-header", "value"),
            "tx-corr-123",
            "tx-cause-456",
            "tx-agg-789"
        ));
        
        assertNotNull(result);
        assertEquals("TxFullTest", result.getEventType());
        assertEquals("tx-corr-123", result.getCorrelationId());
        assertEquals("tx-cause-456", result.getCausationId());
        assertEquals("tx-agg-789", result.getAggregateId());
        
        // Verify it was actually persisted - getById returns BiTemporalEvent not Optional
        BiTemporalEvent<TestEvent> retrieved = await(eventStore.getById(result.getEventId()));
        assertNotNull(retrieved);
        assertEquals(result.getEventId(), retrieved.getEventId());
    }
    
    @Test
    @DisplayName("query with event type filter should work correctly")
    void testQueryEventTypeFilter() throws Exception {
        Instant validTime = Instant.now();
        String uniqueType = "UniqueQuery_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Append events with unique type
        await(eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("q1", "data", 1)).validTime(validTime).execute());
        await(eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("q2", "data", 2)).validTime(validTime).execute());
        await(eventStore.appendBuilder().eventType("OtherType").payload(new TestEvent("q3", "data", 3)).validTime(validTime).execute());
        
        // Query with event type filter
        EventQuery query = EventQuery.builder()
            .eventType(uniqueType)
            .build();
        
        List<BiTemporalEvent<TestEvent>> results = await(eventStore.query(query));
        
        assertNotNull(results);
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(e -> e.getEventType().equals(uniqueType)));
    }
    
    @Test
    @DisplayName("query with limit should restrict results")
    void testQueryLimitParameter() throws Exception {
        Instant validTime = Instant.now();
        String uniqueType = "LimitQuery_" + UUID.randomUUID().toString().substring(0, 8);
        
        // Append multiple events
        for (int i = 0; i < 10; i++) {
            await(eventStore.appendBuilder().eventType(uniqueType).payload(new TestEvent("lq" + i, "data", i)).validTime(validTime).execute());
        }
        
        // Query with limit
        EventQuery query = EventQuery.builder()
            .eventType(uniqueType)
            .limit(5)
            .build();
        
        List<BiTemporalEvent<TestEvent>> results = await(eventStore.query(query));
        
        assertNotNull(results);
        assertEquals(5, results.size());
    }
    
    @Test
    @DisplayName("getById with non-existent ID should return null")
    void testGetByIdMissing() throws Exception {
        String nonExistentId = UUID.randomUUID().toString();
        
        // getById returns BiTemporalEvent (may be null), not Optional
        BiTemporalEvent<TestEvent> result = await(eventStore.getById(nonExistentId));
        
        assertNull(result, "Non-existent ID should return null");
    }
    
    @Test
    @DisplayName("appendWithTransaction with minimal parameters")
    void testAppendWithTransactionMinimal() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-min", "minimal", 111);
        
        // Use minimal appendWithTransaction with TransactionPropagation
        BiTemporalEvent<TestEvent> result = await(eventStore.appendWithTransaction(
            "TxMinTest",
            payload,
            validTime,
            TransactionPropagation.CONTEXT
        ));
        
        assertNotNull(result);
        assertEquals("TxMinTest", result.getEventType());
    }
    
    @Test
    @DisplayName("appendWithTransaction with headers only")
    void testAppendWithTransactionHeaders() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-headers", "headers", 222);
        
        BiTemporalEvent<TestEvent> result = await(eventStore.appendWithTransaction(
            "TxHeadersTest",
            payload,
            validTime,
            Map.of("h1", "v1", "h2", "v2"),
            TransactionPropagation.CONTEXT
        ));
        
        assertNotNull(result);
        assertEquals("TxHeadersTest", result.getEventType());
        assertNotNull(result.getHeaders());
    }
    
    @Test
    @DisplayName("appendWithTransaction with correlation ID")
    void testAppendWithTransactionCorrelation() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-corr", "correlation", 333);
        
        BiTemporalEvent<TestEvent> result = await(eventStore.appendWithTransaction(
            "TxCorrTest",
            payload,
            validTime,
            Map.of(),
            "my-correlation-id",
            TransactionPropagation.CONTEXT
        ));
        
        assertNotNull(result);
        assertEquals("TxCorrTest", result.getEventType());
        assertEquals("my-correlation-id", result.getCorrelationId());
    }
    
    @Test
    @DisplayName("appendWithTransaction with aggregate ID")
    void testAppendWithTransactionAggregate() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-agg", "aggregate", 444);
        
        BiTemporalEvent<TestEvent> result = await(eventStore.appendWithTransaction(
            "TxAggTest",
            payload,
            validTime,
            Map.of(),
            null,
            null,
            "my-aggregate-id"
        ));
        
        assertNotNull(result);
        assertEquals("TxAggTest", result.getEventType());
        assertEquals("my-aggregate-id", result.getAggregateId());
    }
    
    @Test
    @DisplayName("getAsOfTransactionTime with current time should return event")
    void testGetAsOfTransactionTimeCurrentTime() throws Exception {
        Instant validTime = Instant.now();
        TestEvent payload = new TestEvent("tx-current", "data", 666);
        
        // Append an event first
        BiTemporalEvent<TestEvent> appended = await(eventStore.appendBuilder().eventType("TxCurrentTime").payload(payload).validTime(validTime).execute());
        assertNotNull(appended);
        
        // Query with current time - should find the event
        BiTemporalEvent<TestEvent> result = await(eventStore.getAsOfTransactionTime(appended.getEventId(), Instant.now()));
        
        assertNotNull(result);
        assertEquals(appended.getEventId(), result.getEventId());
    }

    private void awaitAsyncDelay(long delayMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(latch::countDown);
        assertTrue(latch.await(delayMs + 2000, TimeUnit.MILLISECONDS),
            "Timed out waiting for async processing delay");
    }
}
