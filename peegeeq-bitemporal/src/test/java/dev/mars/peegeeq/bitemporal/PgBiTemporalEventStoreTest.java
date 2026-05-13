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
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PgBiTemporalEventStore.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-15
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Testcontainers
class PgBiTemporalEventStoreTest {
    private static final Logger logger = LoggerFactory.getLogger(PgBiTemporalEventStoreTest.class);

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private EventStore<TestEvent> eventStore;

    /**
     * Test event class.
     */
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

        @Override
        public String toString() {
            return "TestEvent{" +
                    "id='" + id + '\'' +
                    ", data='" + data + '\'' +
                    ", value=" + value +
                    '}';
        }
    }

    private Future<Void> cleanupDatabase(Vertx vertx) {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool cleanupPool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

        return cleanupPool.withConnection(conn ->
            conn.query("DELETE FROM bitemporal_event_log").execute()
                .recover(throwable -> {
                    if (!throwable.getMessage().contains("does not exist")) {
                        logger.warn("Cleanup operation warning: {}", throwable.getMessage());
                    }
                    return Future.succeededFuture();
                })
                .mapEmpty()
        ).compose(v -> cleanupPool.close());
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Setting up: cleaning database for test isolation");

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.health-check.queue-checks-enabled", "false")
                .build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.BITEMPORAL);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        cleanupDatabase(vertx)
            .compose(v -> manager.start())
            .onSuccess(v -> {
                factory = new BiTemporalEventStoreFactory(vertx, manager);
                eventStore = factory.createEventStore(TestEvent.class, "bitemporal_event_log");
                logger.info("Setup complete");
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "setUp timed out");
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Tearing down");
        if (eventStore != null) {
            try { eventStore.close(); } catch (Exception e) { logger.warn("Event store cleanup: {}", e.getMessage()); }
            eventStore = null;
        }

        if (manager != null) {
            PeeGeeQManager m = manager;
            manager = null;
            m.closeReactive()
                .compose(v -> cleanupDatabase(vertx))
                .onSuccess(v -> testContext.completeNow())
                .onFailure(e -> {
                    logger.warn("Teardown warning: {}", e.getMessage());
                    testContext.completeNow();
                });
        } else {
            testContext.completeNow();
        }

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "tearDown timed out");
    }

    @Test
    void testAppendEvent(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: append a single event and verify all fields");
        TestEvent payload = new TestEvent("test-1", "test data", 42);
        Instant validTime = Instant.now();

        eventStore.appendBuilder()
            .eventType("TestEvent")
            .payload(payload)
            .validTime(validTime)
            .execute()
            .onComplete(testContext.succeeding(event -> testContext.verify(() -> {
                logger.info("Appended event id={}, version={}, eventType={}", event.getEventId(), event.getVersion(), event.getEventType());
                assertNotNull(event);
                assertNotNull(event.getEventId());
                assertEquals("TestEvent", event.getEventType());
                assertEquals(payload, event.getPayload());
                assertEquals(validTime, event.getValidTime());
                assertNotNull(event.getTransactionTime());
                assertEquals(1L, event.getVersion());
                assertNull(event.getPreviousVersionId());
                assertFalse(event.isCorrection());
                assertNull(event.getCorrectionReason());
                testContext.completeNow();
            })));
    }

    @Test
    void testAppendEventWithMetadata(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: append event with headers, correlationId, and aggregateId");
        TestEvent payload = new TestEvent("test-2", "test data", 100);
        Instant validTime = Instant.now();
        Map<String, String> headers = Map.of("source", "test", "version", "1.0");
        String correlationId = "corr-123";
        String aggregateId = "agg-456";

        eventStore.appendBuilder()
            .eventType("TestEvent")
            .payload(payload)
            .validTime(validTime)
            .headers(headers)
            .correlationId(correlationId)
            .aggregateId(aggregateId)
            .execute()
            .onComplete(testContext.succeeding(event -> testContext.verify(() -> {
                assertNotNull(event);
                assertEquals("TestEvent", event.getEventType());
                assertEquals(payload, event.getPayload());
                assertEquals(headers, event.getHeaders());
                assertEquals(correlationId, event.getCorrelationId());
                assertEquals(aggregateId, event.getAggregateId());
                logger.info("Metadata verified: headers={}, correlationId={}, aggregateId={}", headers, correlationId, aggregateId);
                testContext.completeNow();
            })));
    }

    @Test
    void testAppendCorrection(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: append original event then correction, verify version chain");
        TestEvent originalPayload = new TestEvent("test-3", "original data", 50);
        Instant validTime = Instant.now();

        eventStore.appendBuilder()
            .eventType("TestEvent")
            .payload(originalPayload)
            .validTime(validTime)
            .execute()
            .compose(originalEvent -> {
                TestEvent correctedPayload = new TestEvent("test-3", "corrected data", 75);
                return eventStore.appendCorrection(
                    originalEvent.getEventId(), "TestEvent", correctedPayload, validTime, "Data correction"
                ).map(correctionEvent -> Map.entry(originalEvent, correctionEvent));
            })
            .onComplete(testContext.succeeding(pair -> testContext.verify(() -> {
                BiTemporalEvent<TestEvent> originalEvent = pair.getKey();
                BiTemporalEvent<TestEvent> correctionEvent = pair.getValue();
                assertNotNull(correctionEvent);
                assertNotEquals(originalEvent.getEventId(), correctionEvent.getEventId());
                assertEquals("TestEvent", correctionEvent.getEventType());
                assertEquals(2L, correctionEvent.getVersion());
                assertEquals(originalEvent.getEventId(), correctionEvent.getPreviousVersionId());
                assertTrue(correctionEvent.isCorrection());
                assertEquals("Data correction", correctionEvent.getCorrectionReason());
                logger.info("Correction verified: original={} -> correction={}", originalEvent.getEventId(), correctionEvent.getEventId());
                testContext.completeNow();
            })));
    }

    @Test
    void testTransactionTimeIsDbValueNotJvmClock(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: verify transaction_time comes from database clock, not JVM clock");
        TestEvent payload = new TestEvent("clock-skew", "db clock test", 999);
        Instant validTime = Instant.now();

        eventStore.appendBuilder()
            .eventType("ClockSkewTest")
            .payload(payload)
            .validTime(validTime)
            .execute()
            .compose(event -> {
                PgConnectOptions connectOptions = new PgConnectOptions()
                    .setHost(postgres.getHost())
                    .setPort(postgres.getFirstMappedPort())
                    .setDatabase(postgres.getDatabaseName())
                    .setUser(postgres.getUsername())
                    .setPassword(postgres.getPassword());
                Pool pool = PgBuilder.pool().connectingTo(connectOptions).using(vertx).build();

                return pool.withConnection(conn ->
                    conn.preparedQuery("SELECT transaction_time FROM bitemporal_event_log WHERE event_id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(event.getEventId()))
                        .map(rows -> rows.iterator().next().getOffsetDateTime("transaction_time").toInstant())
                ).compose(dbTransactionTime -> pool.close().map(v -> Map.entry(event, dbTransactionTime)));
            })
            .onComplete(testContext.succeeding(pair -> testContext.verify(() -> {
                BiTemporalEvent<TestEvent> event = pair.getKey();
                Instant dbTransactionTime = pair.getValue();
                logger.info("Comparing API transaction_time={} with DB transaction_time={}", event.getTransactionTime(), dbTransactionTime);
                assertNotNull(event.getTransactionTime());
                assertEquals(dbTransactionTime, event.getTransactionTime(), "API and DB transaction_time must match");

                Instant now = Instant.now();
                long secondsDiff = Math.abs(dbTransactionTime.getEpochSecond() - now.getEpochSecond());
                assertTrue(secondsDiff < 30, "DB transaction_time should be within 30s of now (was off by " + secondsDiff + "s)");
                logger.info("Transaction time verified: DB clock used (within {}s of now)", secondsDiff);
                testContext.completeNow();
            })));
    }

    @Test
    void testQueryAllEvents(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: append two events with unique type, query all of that type");
        TestEvent event1 = new TestEvent("test-4", "data 1", 10);
        TestEvent event2 = new TestEvent("test-5", "data 2", 20);
        Instant validTime1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant validTime2 = Instant.now();
        String uniqueEventType = "IntegrationTestEvent";

        eventStore.appendBuilder().eventType(uniqueEventType).payload(event1).validTime(validTime1).execute()
            .compose(v -> eventStore.appendBuilder().eventType(uniqueEventType).payload(event2).validTime(validTime2).execute())
            .compose(v -> eventStore.query(EventQuery.forEventType(uniqueEventType)))
            .onComplete(testContext.succeeding(events -> testContext.verify(() -> {
                assertEquals(2, events.size());
                logger.info("Query returned {} events for type '{}'", events.size(), uniqueEventType);
                testContext.completeNow();
            })));
    }

    @Test
    void testQueryByEventType(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: query events filtered by event type");
        TestEvent event1 = new TestEvent("test-6", "data 1", 30);
        TestEvent event2 = new TestEvent("test-7", "data 2", 40);
        Instant validTime = Instant.now();
        String type1 = "IntegrationTestEventType1";
        String type2 = "IntegrationTestEventType2";

        eventStore.appendBuilder().eventType(type1).payload(event1).validTime(validTime).execute()
            .compose(v -> eventStore.appendBuilder().eventType(type2).payload(event2).validTime(validTime).execute())
            .compose(v -> eventStore.query(EventQuery.forEventType(type1)))
            .onComplete(testContext.succeeding(events -> testContext.verify(() -> {
                assertEquals(1, events.size());
                assertEquals(type1, events.get(0).getEventType());
                assertEquals(event1, events.get(0).getPayload());
                logger.info("Query by type '{}' returned 1 event", type1);
                testContext.completeNow();
            })));
    }

    @Test
    void testQueryByAggregate(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: query events filtered by aggregateId");
        TestEvent event1 = new TestEvent("test-8", "data 1", 60);
        TestEvent event2 = new TestEvent("test-9", "data 2", 70);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TestEvent").payload(event1).validTime(validTime)
            .headers(Map.of()).correlationId("corr-1").aggregateId("agg-1").execute()
            .compose(v -> eventStore.appendBuilder().eventType("TestEvent").payload(event2).validTime(validTime)
                .headers(Map.of()).correlationId("corr-2").aggregateId("agg-2").execute())
            .compose(v -> eventStore.query(EventQuery.forAggregate("agg-1")))
            .onComplete(testContext.succeeding(events -> testContext.verify(() -> {
                assertEquals(1, events.size());
                assertEquals("agg-1", events.get(0).getAggregateId());
                assertEquals(event1, events.get(0).getPayload());
                logger.info("Query by aggregate 'agg-1' returned 1 event");
                testContext.completeNow();
            })));
    }

    @Test
    void testQueryByAggregateAndEventType(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: query events filtered by both aggregateId and eventType using builder");
        TestEvent event1 = new TestEvent("test-agg-type-1", "data 1", 100);
        TestEvent event2 = new TestEvent("test-agg-type-2", "data 2", 200);
        TestEvent event3 = new TestEvent("test-agg-type-3", "data 3", 300);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TypeA").payload(event1).validTime(validTime).headers(Map.of()).correlationId("corr-1").aggregateId("agg-1").execute()
            .compose(v -> eventStore.appendBuilder().eventType("TypeB").payload(event2).validTime(validTime).headers(Map.of()).correlationId("corr-2").aggregateId("agg-1").execute())
            .compose(v -> eventStore.appendBuilder().eventType("TypeA").payload(event3).validTime(validTime).headers(Map.of()).correlationId("corr-3").aggregateId("agg-2").execute())
            .compose(v -> eventStore.query(EventQuery.builder().aggregateId("agg-1").eventType("TypeA").build()))
            .onComplete(testContext.succeeding(events -> testContext.verify(() -> {
                assertEquals(1, events.size());
                assertEquals("agg-1", events.get(0).getAggregateId());
                assertEquals("TypeA", events.get(0).getEventType());
                assertEquals(event1, events.get(0).getPayload());
                logger.info("Compound query (agg-1 + TypeA) returned 1 event");
                testContext.completeNow();
            })));
    }

    @Test
    void testQueryByAggregateAndEventTypeConvenienceMethod(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: query by aggregate+type using convenience method forAggregateAndType");
        TestEvent event1 = new TestEvent("test-convenience-1", "data 1", 100);
        TestEvent event2 = new TestEvent("test-convenience-2", "data 2", 200);
        TestEvent event3 = new TestEvent("test-convenience-3", "data 3", 300);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("OrderCreated").payload(event1).validTime(validTime).headers(Map.of()).correlationId("corr-1").aggregateId("order-123").execute()
            .compose(v -> eventStore.appendBuilder().eventType("OrderUpdated").payload(event2).validTime(validTime).headers(Map.of()).correlationId("corr-2").aggregateId("order-123").execute())
            .compose(v -> eventStore.appendBuilder().eventType("OrderCreated").payload(event3).validTime(validTime).headers(Map.of()).correlationId("corr-3").aggregateId("order-456").execute())
            .compose(v -> eventStore.query(EventQuery.forAggregateAndType("order-123", "OrderCreated")))
            .onComplete(testContext.succeeding(events -> testContext.verify(() -> {
                assertEquals(1, events.size());
                assertEquals("order-123", events.get(0).getAggregateId());
                assertEquals("OrderCreated", events.get(0).getEventType());
                assertEquals(event1, events.get(0).getPayload());
                logger.info("Convenience method forAggregateAndType correctly filtered to 1 matching event");
                testContext.completeNow();
            })));
    }

    @Test
    void testGetById(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: retrieve a specific event by its ID");
        TestEvent payload = new TestEvent("test-10", "test data", 80);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TestEvent").payload(payload).validTime(validTime).execute()
            .compose(originalEvent -> eventStore.getById(originalEvent.getEventId())
                .map(retrievedEvent -> Map.entry(originalEvent, retrievedEvent)))
            .onComplete(testContext.succeeding(pair -> testContext.verify(() -> {
                assertNotNull(pair.getValue());
                assertEquals(pair.getKey().getEventId(), pair.getValue().getEventId());
                assertEquals(pair.getKey().getPayload(), pair.getValue().getPayload());
                logger.info("getById returned matching event with id={}", pair.getValue().getEventId());
                testContext.completeNow();
            })));
    }

    @Test
    void testGetAllVersions(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: append original + correction, then getAllVersions to retrieve version chain");
        TestEvent originalPayload = new TestEvent("test-11", "original", 90);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TestEvent").payload(originalPayload).validTime(validTime).execute()
            .compose(originalEvent -> {
                TestEvent correctedPayload = new TestEvent("test-11", "corrected", 95);
                return eventStore.appendCorrection(originalEvent.getEventId(), "TestEvent", correctedPayload, validTime, "Correction")
                    .compose(v -> eventStore.getAllVersions(originalEvent.getEventId()));
            })
            .onComplete(testContext.succeeding(versions -> testContext.verify(() -> {
                assertEquals(2, versions.size());
                assertEquals(1L, versions.get(0).getVersion());
                assertEquals(2L, versions.get(1).getVersion());
                assertFalse(versions.get(0).isCorrection());
                assertTrue(versions.get(1).isCorrection());
                logger.info("getAllVersions returned {} versions", versions.size());
                testContext.completeNow();
            })));
    }

    @Test
    void testGetStats(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: append events of different types + correction, then verify stats");
        TestEvent event1 = new TestEvent("test-12", "data 1", 100);
        TestEvent event2 = new TestEvent("test-13", "data 2", 200);
        Instant validTime = Instant.now();

        eventStore.appendBuilder().eventType("TestEvent").payload(event1).validTime(validTime).execute()
            .compose(originalEvent ->
                eventStore.appendBuilder().eventType("OtherEvent").payload(event2).validTime(validTime).execute()
                    .compose(v -> eventStore.appendCorrection(originalEvent.getEventId(), "TestEvent", event1, validTime, "Test correction")))
            .compose(v -> eventStore.getStats())
            .onComplete(testContext.succeeding(stats -> testContext.verify(() -> {
                assertEquals(3, stats.getTotalEvents());
                assertEquals(1, stats.getTotalCorrections());
                assertTrue(stats.getEventCountsByType().containsKey("TestEvent"));
                assertTrue(stats.getEventCountsByType().containsKey("OtherEvent"));
                assertEquals(2L, stats.getEventCountsByType().get("TestEvent").longValue());
                assertEquals(1L, stats.getEventCountsByType().get("OtherEvent").longValue());
                assertNotNull(stats.getOldestEventTime());
                assertNotNull(stats.getNewestEventTime());
                assertTrue(stats.getStorageSizeBytes() > 0);
                logger.info("Stats verified: totalEvents={}, corrections={}", stats.getTotalEvents(), stats.getTotalCorrections());
                testContext.completeNow();
            })));
    }

    @Test
    void testConstructorRejectsSchemaQualifiedTableName(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: constructor rejects schema-qualified table name 'public.bitemporal_event_log'");
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = constructor rejects schema-qualified table name");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new PgBiTemporalEventStore<>(vertx, manager, TestEvent.class, "public.bitemporal_event_log", new ObjectMapper()));
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected constructor validation failure = {}", error.getMessage());
        assertTrue(error.getMessage().contains("unqualified"),
            "Expected schema-qualified table name to be rejected");
        logger.info("Rejected as expected: {}", error.getMessage());
        testContext.completeNow();
    }

    @Test
    void testConstructorRejectsUnsafeTableNameCharacters(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: constructor rejects SQL-injection-style table name");
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Negative-path case = constructor rejects unsafe table name characters");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new PgBiTemporalEventStore<>(vertx, manager, TestEvent.class,
                "bitemporal_event_log;DROP TABLE bitemporal_event_log;--", new ObjectMapper()));
        logger.error("THIS IS AN INTENTIONAL TEST ERROR: Captured expected constructor validation failure = {}", error.getMessage());
        logger.info("Unsafe table name rejected as expected");
        testContext.completeNow();
    }

    @Test
    void testPublicConstructorsAlignWithReactiveNotificationHandlerStyle(Vertx vertx, VertxTestContext testContext) {
        logger.info("Test: verify PgBiTemporalEventStore exposes exactly 2 public constructors (5-arg and 6-arg)");
        Constructor<?>[] constructors = PgBiTemporalEventStore.class.getConstructors();

        assertEquals(2, constructors.length, "Expected only convenience and explicit constructors");

        assertTrue(Arrays.stream(constructors)
            .anyMatch(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[] {
                Vertx.class,
                PeeGeeQManager.class,
                Class.class,
                String.class,
                ObjectMapper.class
            })), "Expected 5-argument convenience constructor");

        assertTrue(Arrays.stream(constructors)
            .anyMatch(constructor -> Arrays.equals(constructor.getParameterTypes(), new Class<?>[] {
                Vertx.class,
                PeeGeeQManager.class,
                Class.class,
                String.class,
                ObjectMapper.class,
                String.class
            })), "Expected 6-argument explicit constructor with clientId");
        logger.info("Constructor shape verified: 5-arg convenience + 6-arg explicit with clientId");
        testContext.completeNow();
    }
}
