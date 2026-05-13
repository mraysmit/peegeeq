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
 * Tests that PgBiTemporalEventStore.close() logs at ERROR level (not WARN)
 * when resource close operations fail. Covers the 3 WARN→ERROR changes in close():
 *
 * <ol>
 *   <li>Reactive notification handler close failure</li>
 *   <li>Reactive pool close failure</li>
 *   <li>Pipelined client close failure</li>
 * </ol>
 *
 * Positive tests: trigger failure conditions → verify ERROR logged.
 * Negative tests: clean close → verify no ERROR logged.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);

        Properties testProps = PeeGeeQTestConfig.builder()
                .from(postgres)
                .property("peegeeq.database.ssl.enabled", "false")
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "5")
                .property("peegeeq.health.check-interval", "PT5S")
                .property("peegeeq.metrics.reporting-interval", "PT10S")
                .property("peegeeq.migration.enabled", "false")
                .property("peegeeq.migration.auto-migrate", "false")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        // Attach log capture to PgBiTemporalEventStore's logger
        eventStoreLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PgBiTemporalEventStore.class);
        logCapture = new LogCaptureAppender();
        logCapture.setContext(eventStoreLogger.getLoggerContext());
        logCapture.start();
        eventStoreLogger.addAppender(logCapture);

        manager.start()
                .compose(v -> {
                    eventStore = new PgBiTemporalEventStore<>(
                            vertx, manager, JsonObject.class, "bitemporal_log_level_test",
                            new ObjectMapper());
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        eventStoreLogger.detachAppender(logCapture);
        logCapture.stop();

        Future<Void> closeChain = Future.succeededFuture();

        if (eventStore != null) {
            closeChain = closeChain.compose(v -> eventStore.close()
                    .transform(ar -> Future.succeededFuture()));
        }
        if (manager != null) {
            closeChain = closeChain.compose(v -> manager.closeReactive()
                    .transform(ar -> Future.succeededFuture()));
        }

        closeChain.onSuccess(v -> testContext.completeNow())
                  .onFailure(e -> { log.warn("Teardown close error: {}", e.getMessage()); testContext.completeNow(); });

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Negative: clean close with DB alive produces no ERROR from close chain")
    void testCleanCloseNoErrorLogs(VertxTestContext testContext) throws InterruptedException {
        logCapture.clear();
        eventStore.close()
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    boolean hasCloseError = errors.stream().anyMatch(e ->
                            e.getFormattedMessage().contains("Error closing reactive notification handler") ||
                            e.getFormattedMessage().contains("Error closing reactive pool") ||
                            e.getFormattedMessage().contains("Error closing pipelined client"));

                    assertFalse(hasCloseError,
                            "Clean close should not produce ERROR logs from close chain, but got: " +
                                    errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    eventStore = null; // Prevent double-close in tearDown
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Positive: close after DB shutdown logs ERROR (not WARN) for pool/resource close failures")
    void testCloseAfterDbShutdownLogsError(VertxTestContext testContext) throws InterruptedException {
        // Use a separate container we can stop
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = PostgreSQLTestConstants.createStandardContainer();
        ownContainer.start();
        PeeGeeQTestSchemaInitializer.initializeSchema(ownContainer, SchemaComponent.ALL);

        Properties ownProps = PeeGeeQTestConfig.builder()
                .from(ownContainer)
                .property("peegeeq.database.ssl.enabled", "false")
                .property("peegeeq.database.pool.min-size", "1")
                .property("peegeeq.database.pool.max-size", "5")
                .property("peegeeq.health.check-interval", "PT5S")
                .property("peegeeq.metrics.reporting-interval", "PT10S")
                .property("peegeeq.migration.enabled", "false")
                .property("peegeeq.migration.auto-migrate", "false")
                .build();
        PeeGeeQConfiguration ownConfig = new PeeGeeQConfiguration("default", ownProps);
        PeeGeeQManager ownManager = new PeeGeeQManager(ownConfig, new SimpleMeterRegistry());

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PgBiTemporalEventStore.class);
        LogCaptureAppender ownCapture = new LogCaptureAppender();
        ownCapture.setContext(logger.getLoggerContext());
        ownCapture.start();
        logger.addAppender(ownCapture);

        ownManager.start()
                .compose(v -> {
                    PgBiTemporalEventStore<JsonObject> ownStore = new PgBiTemporalEventStore<>(
                            vertx, ownManager, JsonObject.class, "bitemporal_log_level_test2",
                            new ObjectMapper());

                    // Stop the database to cause close failures.
                    // Connection-abort errors from background jobs (e.g. DeadConsumerDetectionJob) below are expected.
                    log.info("[TEST] Intentionally stopping database container to trigger close failure path subsequent connection errors are expected");
                    ownContainer.stop();
                    ownCapture.clear();

                    return ownStore.close()
                            .compose(v2 -> ownManager.closeReactive().transform(ar -> Future.succeededFuture()));
                })
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> warns = ownCapture.eventsAtLevel(Level.WARN);

                    // Verify close chain failures are NOT at WARN (they should be at ERROR)
                    boolean hasWarnForClose = warns.stream().anyMatch(e ->
                            e.getFormattedMessage().contains("Error closing reactive notification handler") ||
                            e.getFormattedMessage().contains("Error closing reactive pool") ||
                            e.getFormattedMessage().contains("Error closing pipelined client"));
                    assertFalse(hasWarnForClose,
                            "Resource close failures should be at ERROR level, not WARN");

                    logger.detachAppender(ownCapture);
                    ownCapture.stop();
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Negative: double close produces no ERROR (second close is a no-op)")
    void testDoubleCloseNoError(VertxTestContext testContext) throws InterruptedException {
        eventStore.close()
                .compose(v -> {
                    logCapture.clear();
                    return eventStore.close();
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    assertTrue(errors.isEmpty(),
                            "Double close should not produce ERROR logs (second close is a no-op), but got: " +
                                    errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    eventStore = null;
                    testContext.completeNow();
                })));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // --- Helpers ---

    static final class LogCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }

        List<ILoggingEvent> eventsAtLevel(Level level) {
            return events.stream()
                    .filter(e -> e.getLevel().equals(level))
                    .toList();
        }

        void clear() {
            events.clear();
        }
    }
}
