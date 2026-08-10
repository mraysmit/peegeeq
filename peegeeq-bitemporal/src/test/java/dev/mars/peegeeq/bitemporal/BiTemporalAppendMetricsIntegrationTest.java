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
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the bitemporal append instrumentation (metrics-stack review backlog, 2026-08-09).
 *
 * <p>Before this, the bitemporal module recorded ZERO metrics: appends fed nothing, so a
 * system doing only event-store writes scraped as completely idle. Every successful append
 * now records through the manager's {@code PeeGeeQMetrics} via the existing
 * {@code recordMessageSent(topic, durationMs)} seam — the same meters the native and outbox
 * producers feed ({@code peegeeq.messages.sent}, {@code .by.topic}, {@code
 * peegeeq.message.send.time}), with topic = the event store's table name. Core-produces:
 * no REST involvement.
 *
 * <p>Batch semantics: the counter counts EVENTS (N per batch); the timer gets ONE sample
 * per batch — the single measured write. N identical samples would fabricate distribution
 * mass. A failed append records nothing (producer precedent: send failures propagate via
 * the Future and are not counted as sent).
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class BiTemporalAppendMetricsIntegrationTest {

    private static final String TABLE = "bitemporal_event_log";

    @Container
    @SuppressWarnings("resource") // Managed by Testcontainers framework
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private BiTemporalEventStoreFactory factory;
    private PgBiTemporalEventStore<TestEvent> eventStore;
    private Vertx vertx;

    public static class TestEvent {
        private final String id;

        @JsonCreator
        public TestEvent(@JsonProperty("id") String id) {
            this.id = id;
        }

        public String getId() { return id; }
    }

    private Future<Void> cleanupDatabase() {
        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getFirstMappedPort())
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        Pool cleanupPool = PgBuilder.pool()
            .connectingTo(connectOptions)
            .using(vertx)
            .build();

        return cleanupPool.withConnection(conn ->
            conn.query("SELECT to_regclass('public.bitemporal_event_log') AS table_name").execute()
                .compose(rows -> {
                    if (!rows.iterator().hasNext() || rows.iterator().next().getValue("table_name") == null) {
                        return Future.succeededFuture();
                    }
                    return conn.query("TRUNCATE TABLE bitemporal_event_log").execute().mapEmpty();
                })
        ).compose(v -> cleanupPool.close());
    }

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws Exception {
        this.vertx = vertx;

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.health-check.queue-checks-enabled", "false")
                .build();

        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.BITEMPORAL);

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        cleanupDatabase()
            .compose(v -> manager.start())
            .compose(v -> {
                factory = new BiTemporalEventStoreFactory(vertx, manager);
                eventStore = (PgBiTemporalEventStore<TestEvent>) factory.createEventStore(TestEvent.class, TABLE);
                return Future.succeededFuture();
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 30);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        Future<Void> closeFuture = Future.succeededFuture();
        if (eventStore != null) {
            closeFuture = eventStore.close();
            eventStore = null;
        }
        closeFuture
            .compose(v -> {
                if (manager != null) {
                    PeeGeeQManager m = manager;
                    manager = null;
                    return m.closeReactive();
                }
                return Future.succeededFuture();
            })
            .compose(v -> cleanupDatabase())
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
        awaitSuccess(testContext, 30);
    }

    private Counter sentByTopic() {
        return manager.getMeterRegistry().find("peegeeq.messages.sent.by.topic")
                .tag("topic", TABLE).counter();
    }

    private Timer sendTime() {
        return manager.getMeterRegistry().find("peegeeq.message.send.time")
                .tag("topic", TABLE).timer();
    }

    @Test
    @DisplayName("append feeds the send meters: absent before, counter 1 + timer 1 after")
    void appendFeedsSendMeters(VertxTestContext testContext) throws Exception {
        // Absence before: nothing has been appended, so the per-topic meters must
        // not exist yet (absence-not-zero contract).
        assertNull(sentByTopic(), "per-topic sent counter must not exist before any append");
        assertNull(sendTime(), "send-time timer must not exist before any append");

        eventStore.append("metrics.test", new TestEvent("e1"), Instant.now())
            .onComplete(testContext.succeeding(event -> testContext.verify(() -> {
                Counter counter = sentByTopic();
                Timer timer = sendTime();
                assertNotNull(counter, "append must feed peegeeq.messages.sent.by.topic");
                assertEquals(1.0, counter.count(), 0.0001);
                assertNotNull(timer, "append must feed peegeeq.message.send.time");
                assertEquals(1, timer.count());
                testContext.completeNow();
            })));
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("appendBatch counts every event but records one timer sample")
    void appendBatchCountsEventsOnceTimesOnce(VertxTestContext testContext) throws Exception {
        List<PgBiTemporalEventStore.BatchEventData<TestEvent>> events = new ArrayList<>();
        Instant validTime = Instant.now();
        for (int i = 0; i < 5; i++) {
            events.add(new PgBiTemporalEventStore.BatchEventData<>(
                "metrics.batch", new TestEvent("b-" + i), validTime, Map.of(), null, null));
        }

        eventStore.appendBatch(events)
            .onComplete(testContext.succeeding(results -> testContext.verify(() -> {
                assertEquals(5, results.size());
                Counter counter = sentByTopic();
                Timer timer = sendTime();
                assertNotNull(counter);
                assertEquals(5.0, counter.count(), 0.0001,
                    "the counter counts EVENTS: 5 events in the batch");
                assertNotNull(timer);
                assertEquals(1, timer.count(),
                    "the timer gets ONE sample per batch - the single measured write");
                testContext.completeNow();
            })));
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("appendCorrection also records a send")
    void appendCorrectionAlsoRecords(VertxTestContext testContext) throws Exception {
        eventStore.append("metrics.test", new TestEvent("original"), Instant.now())
            .compose(original -> eventStore.appendCorrection(
                original.getEventId(), "metrics.test", new TestEvent("corrected"),
                Instant.now(), "test correction"))
            .onComplete(testContext.succeeding(correction -> testContext.verify(() -> {
                Counter counter = sentByTopic();
                assertNotNull(counter);
                assertEquals(2.0, counter.count(), 0.0001,
                    "original append + correction = 2 sends");
                testContext.completeNow();
            })));
        awaitSuccess(testContext, 10);
    }

    @Test
    @DisplayName("a failed append records nothing")
    void failedAppendRecordsNothing(VertxTestContext testContext) throws Exception {
        // Dependency failure mode: the store is closed, the append fails, and no
        // send is recorded - a failed write is not a sent message.
        eventStore.close()
            .compose(v -> eventStore.append("metrics.test", new TestEvent("never"), Instant.now()))
            .onComplete(testContext.failing(error -> testContext.verify(() -> {
                assertNull(sentByTopic(), "a failed append must not feed the sent counter");
                assertNull(sendTime(), "a failed append must not feed the send timer");
                testContext.completeNow();
            })));
        awaitSuccess(testContext, 10);
    }

    private void awaitSuccess(VertxTestContext testContext, long timeoutSeconds) {
        boolean completed;
        try {
            completed = testContext.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting asynchronous test completion", e);
        }
        assertTrue(completed, "Test timed out after " + timeoutSeconds + " seconds");
        if (testContext.failed()) {
            throw new AssertionError("Asynchronous test flow failed", testContext.causeOfFailure());
        }
    }
}
