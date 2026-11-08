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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.api.lifecycle.CloseSteps;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The close of {@link PgBiTemporalEventStore} (finding 6).
 *
 * <p>The store closes its notification handler, its reactive pool and its pipelined client as
 * {@link CloseSteps}: every step runs, a failed step is logged at ERROR, and the first failure
 * fails {@code close()}. The step mechanism is tested with real failures in
 * {@code CloseStepsCoreTest}; none of these three resources can be made to fail to close here
 * without a mock, so this class pins the outcomes that real conditions produce.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
// The log capture is attached to the shared store logger. Run alone, so it sees only the lines
// of the test that reads it.
@Isolated
class PgBiTemporalEventStoreCloseLogLevelTest {

    private static final Logger log = LoggerFactory.getLogger(PgBiTemporalEventStoreCloseLogLevelTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private Vertx vertx;
    private PeeGeeQManager manager;
    private PgBiTemporalEventStore<JsonObject> eventStore;
    private LogCaptureAppender logCapture;
    private ch.qos.logback.classic.Logger eventStoreLogger;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        this.vertx = vertx;
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", properties(postgres)), new SimpleMeterRegistry());

        eventStoreLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PgBiTemporalEventStore.class);
        logCapture = new LogCaptureAppender();
        logCapture.setContext(eventStoreLogger.getLoggerContext());
        logCapture.start();
        eventStoreLogger.addAppender(logCapture);

        manager.start()
                .onSuccess(v -> {
                    eventStore = new PgBiTemporalEventStore<>(
                            vertx, manager, JsonObject.class, "bitemporal_log_level_test", new ObjectMapper());
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        eventStoreLogger.detachAppender(logCapture);
        logCapture.stop();

        // Both close; a failure of either fails the teardown, because it leaks connections.
        CloseSteps steps = CloseSteps.after(Future.succeededFuture(), log);
        if (eventStore != null) {
            steps.then("event store", eventStore::close);
        }
        if (manager != null) {
            steps.then("manager", manager::closeReactive);
        }
        steps.result()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Clean close with DB alive succeeds and logs no ERROR")
    void testCleanCloseNoErrorLogs(VertxTestContext testContext) throws InterruptedException {
        logCapture.clear();
        eventStore.close()
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertEquals(List.of(), messagesAt(Level.ERROR));
                    eventStore = null; // Prevent double-close in tearDown
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Close after the database stopped succeeds: the pool and client close locally")
    void testCloseAfterDbShutdownSucceeds(VertxTestContext testContext) throws InterruptedException {
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = PostgreSQLTestConstants.createStandardContainer();
        ownContainer.start();
        PeeGeeQTestSchemaInitializer.initializeSchema(ownContainer, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.ALL);
        PeeGeeQManager ownManager = new PeeGeeQManager(
                new PeeGeeQConfiguration("default", properties(ownContainer)), new SimpleMeterRegistry());
        PgBiTemporalEventStore<JsonObject>[] ownStore = newStoreHolder();

        ownManager.start()
                .compose(v -> {
                    ownStore[0] = new PgBiTemporalEventStore<>(
                            vertx, ownManager, JsonObject.class, "bitemporal_log_level_test2", new ObjectMapper());
                    log.info("[TEST] Stopping the database before close; connection errors from background jobs are expected");
                    // Stopping a container blocks, so it runs off the event loop.
                    return vertx.<Void>executeBlocking(() -> {
                        ownContainer.stop();
                        return null;
                    });
                })
                .compose(v -> {
                    logCapture.clear();
                    return ownStore[0].close();
                })
                .compose(v -> ownManager.closeReactive())
                .onSuccess(v -> testContext.verify(() -> {
                    assertEquals(List.of(), messagesAt(Level.ERROR),
                            "closing after the database stopped is not a close failure");
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Double close produces no ERROR (second close is a no-op)")
    void testDoubleCloseNoError(VertxTestContext testContext) throws InterruptedException {
        eventStore.close()
                .compose(v -> {
                    logCapture.clear();
                    return eventStore.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertEquals(List.of(), messagesAt(Level.ERROR),
                            "the second close is a no-op and must log no ERROR");
                    eventStore = null;
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // --- Helpers ---

    private static Properties properties(PostgreSQLContainer container) {
        return PeeGeeQTestConfig.builder()
                .from(container)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.database.ssl.enabled", "false")
                .property("peegeeq.database.pool.max-size", "5")
                .property("peegeeq.health.check-interval", "PT5S")
                .property("peegeeq.metrics.reporting-interval", "PT10S")
                .property("peegeeq.migration.enabled", "false")
                .property("peegeeq.migration.auto-migrate", "false")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static PgBiTemporalEventStore<JsonObject>[] newStoreHolder() {
        return (PgBiTemporalEventStore<JsonObject>[]) new PgBiTemporalEventStore<?>[1];
    }

    private List<String> messagesAt(Level level) {
        return logCapture.eventsAtLevel(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    static final class LogCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }

        List<ILoggingEvent> eventsAtLevel(Level level) {
            synchronized (events) {
                return events.stream()
                        .filter(e -> e.getLevel().equals(level))
                        .toList();
            }
        }

        void clear() {
            events.clear();
        }
    }
}
