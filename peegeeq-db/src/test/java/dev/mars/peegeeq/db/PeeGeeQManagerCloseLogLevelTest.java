package dev.mars.peegeeq.db;

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
import dev.mars.peegeeq.api.lifecycle.PeeGeeQCloseHook;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cleanup steps of {@link PeeGeeQManager#closeReactive()} (finding 6).
 *
 * <p>Every step runs whatever the earlier steps did. A step that fails is logged at ERROR with
 * its cause, because a failed close can leave connections or threads open, and the returned
 * Future fails with the first failure; later failures are suppressed on it. Before the
 * correction each step was chained with {@code eventually}, which discards the step's failure:
 * a failed close was logged at WARN and {@code closeReactive()} reported success.</p>
 *
 * <p>A failing close hook is the cleanup failure a test can produce with real objects; it
 * exercises the same step chain as the other steps.</p>
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
// The log capture is attached to the shared PeeGeeQManager logger. Run alone, so it sees only
// the lines of the test that reads it.
@org.junit.jupiter.api.parallel.Isolated
public class PeeGeeQManagerCloseLogLevelTest {

    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private LogCaptureAppender logCapture;
    private ch.qos.logback.classic.Logger managerLogger;

    @org.junit.jupiter.api.BeforeAll
    static void initDb() {
        initializeSchemaFor(postgres);
    }

    @BeforeEach
    void setUp() {
        managerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PeeGeeQManager.class);
        logCapture = new LogCaptureAppender();
        logCapture.setContext(managerLogger.getLoggerContext());
        logCapture.start();
        managerLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        managerLogger.detachAppender(logCapture);
        logCapture.stop();

        if (manager != null) {
            manager.closeReactive()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
    }

    @Test
    @DisplayName("Negative: clean close with DB alive succeeds and logs no cleanup ERROR")
    void testCleanCloseNoErrorLogs(VertxTestContext testContext) {
        manager = newManager(postgres);

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertEquals(List.of(), messagesAt(Level.ERROR),
                            "a clean close must not log ERROR");
                    manager = null;
                    testContext.completeNow();
                })));
    }

    @Test
    @Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
    @DisplayName("Close after the database stopped still closes every component and succeeds")
    void testCloseAfterDbShutdownSucceeds(VertxTestContext testContext) {
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = PostgreSQLTestConstants.createStandardContainer();
        ownContainer.start();
        initializeSchemaFor(ownContainer);
        manager = newManager(ownContainer);

        manager.start()
                .compose(v -> Vertx.currentContext().owner().<Void>executeBlocking(() -> {
                    // Stop the database off the event loop to avoid blocking it
                    ownContainer.stop();
                    return null;
                }))
                .compose(v -> {
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    // Pools close locally; a stopped database is not a close failure.
                    assertTrue(messagesAt(Level.INFO).contains("Client factory closed successfully"),
                            "the client factory must be closed: " + messagesAt(Level.INFO));
                    assertEquals(List.of(), messagesAt(Level.ERROR));
                    manager = null;
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Close without start produces no ERROR (stop() is a no-op when not started)")
    void testCloseWithoutStartNoStopError(VertxTestContext testContext) {
        manager = newManager(postgres);

        logCapture.clear();
        manager.closeReactive()
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    assertEquals(List.of(), messagesAt(Level.ERROR));
                    manager = null;
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Negative: Vert.x close with RejectedExecutionException stays below ERROR")
    void testVertxCloseExpectedExceptionNotLoggedAsError(VertxTestContext testContext) {
        // Manager owns its own Vert.x (default constructor path)
        manager = newManager(postgres);

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    boolean hasVertxCloseError = messagesAt(Level.ERROR).stream()
                            .anyMatch(message -> message.contains("Error closing Vert.x instance"));
                    assertFalse(hasVertxCloseError,
                            "Expected Vert.x RejectedExecutionException during close should not be logged at ERROR");
                    manager = null;
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("A close hook that fails fails closeReactive, is logged at ERROR, and later steps still run")
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.PeeGeeQManager",
            message = "Close step 'close hook failing-hook' failed:",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    void failedCloseHookFailsCloseAndLaterStepsRun(VertxTestContext testContext) {
        manager = newManager(postgres);
        AtomicBoolean laterHookRan = new AtomicBoolean();
        manager.registerCloseHook(hook("failing-hook",
                () -> Future.failedFuture(new IllegalStateException("hook could not release its resource"))));
        manager.registerCloseHook(hook("later-hook", () -> {
            laterHookRan.set(true);
            return Future.succeededFuture();
        }));

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onComplete(result -> testContext.verify(() -> {
                    assertTrue(result.failed(), "a failed close step must fail closeReactive()");
                    assertEquals("hook could not release its resource", result.cause().getMessage());
                    assertTrue(laterHookRan.get(), "a failed hook must not stop the later hooks");
                    assertTrue(messagesAt(Level.INFO).contains("Client factory closed successfully"),
                            "a failed hook must not stop the pool close");
                    manager = null;
                    testContext.completeNow();
                }));
    }

    @Test
    @DisplayName("A close hook that throws is a failed step, not a discarded one")
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.PeeGeeQManager",
            message = "Close step 'close hook throwing-hook' failed:",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalArgumentException.class)
    void throwingCloseHookFailsClose(VertxTestContext testContext) {
        manager = newManager(postgres);
        manager.registerCloseHook(hook("throwing-hook", () -> {
            throw new IllegalArgumentException("hook threw");
        }));

        manager.start()
                .compose(v -> manager.closeReactive())
                .onComplete(result -> testContext.verify(() -> {
                    assertTrue(result.failed(), "a thrown close step must fail closeReactive()");
                    assertInstanceOf(IllegalArgumentException.class, result.cause());
                    manager = null;
                    testContext.completeNow();
                }));
    }

    @Test
    @DisplayName("With two failed steps the first is returned and the second is suppressed on it")
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.PeeGeeQManager",
            message = "Close step 'close hook first-hook' failed:",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.PeeGeeQManager",
            message = "Close step 'close hook second-hook' failed:",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.CAUSE_CHAIN_CONTAINS,
            throwableType = IllegalStateException.class)
    void firstOfTwoFailuresIsReturned(VertxTestContext testContext) {
        manager = newManager(postgres);
        manager.registerCloseHook(hook("first-hook",
                () -> Future.failedFuture(new IllegalStateException("first"))));
        manager.registerCloseHook(hook("second-hook",
                () -> Future.failedFuture(new IllegalStateException("second"))));

        manager.start()
                .compose(v -> manager.closeReactive())
                .onComplete(result -> testContext.verify(() -> {
                    assertTrue(result.failed());
                    assertEquals("first", result.cause().getMessage());
                    assertEquals(1, result.cause().getSuppressed().length, "the second failure must be kept");
                    assertEquals("second", result.cause().getSuppressed()[0].getMessage());
                    manager = null;
                    testContext.completeNow();
                }));
    }

    // --- Helpers ---

    private static PeeGeeQManager newManager(PostgreSQLContainer container) {
        Properties testProps = PeeGeeQTestConfig.builder()
                .from(container)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.migration.enabled", "false")
                .property("peegeeq.migration.auto-migrate", "false")
                .build();
        return new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
    }

    private static PeeGeeQCloseHook hook(String name, java.util.function.Supplier<Future<Void>> close) {
        return new PeeGeeQCloseHook() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Future<Void> closeReactive() {
                return close.get();
            }
        };
    }

    private List<String> messagesAt(Level level) {
        return logCapture.eventsAtLevel(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void initializeSchemaFor(PostgreSQLContainer container) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
                container, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
    }

    /** Log capture appender using logback's AppenderBase. */
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
