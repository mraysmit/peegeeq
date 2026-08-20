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
import dev.mars.peegeeq.api.health.ComponentHealthState;
import dev.mars.peegeeq.api.health.HealthStatusInfo;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.logging.ExpectedErrorLog;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests protecting against timer callback race conditions in {@link PeeGeeQManager}.
 *
 * <h2>Behaviours under test</h2>
 * <ol>
 *   <li><b>Clean-close guard</b> ({@code testNoTimerFailuresDuringCleanClose}): when
 *       {@code closeReactive()} is called with the DB alive, no timer failure logs must appear.
 *       The {@code if (closing) return;} guard must prevent any in-flight or pending tick
 *       from reaching the DB after close begins.</li>
 *
 *   <li><b>Consecutive-failure escalation</b> ({@code testTimerFailuresEscalateWarnToError}):
 *       when the DB is stopped while the manager is running, the first failure logs at WARN
 *       with its complete cause chain. Persistent failures produce rate-limited ERROR summaries
 *       with the consecutive count and surface as an UNHEALTHY depth-cache component.</li>
 *
 *   <li><b>Closing-guard race</b> ({@code testClosingGuardPreventsTimerCallbacksAfterClose}):
 *       with 1 s timer intervals, a tick may fire in the window between {@code closing=true}
 *       and {@code cancelTimer()}. The guard must intercept it before any DB call is made.</li>
 *
 *   <li><b>In-flight fail-fast</b> ({@code testInFlightTasksFailFast}): background tasks
 *       ({@code refreshDepthCache}, {@code persistMetrics}) invoked directly after
 *       {@code closeReactive()} must complete without hitting the DB.</li>
 *
 *   <li><b>Fast-timer immediate-close</b> ({@code testFastTimersWithImmediateClose}): same
 *       race as (3) but with a dedicated property set and an immediate close after 2.5 s of
 *       successful ticks, maximising the chance of a tick firing mid-close.</li>
 * </ol>
 *
 * <h2>Strategy</h2>
 * <p>All tests set {@code peegeeq.metrics.reporting-interval=PT1S} and
 * {@code peegeeq.metrics.depth-cache-interval=PT1S} (the minimum allowed by configuration
 * validation) so timer ticks fire fast enough to observe multiple successes or failures
 * within a few seconds. Other background jobs (recovery, dead-consumer detection,
 * consumer-group retry, migration) are disabled to keep the captured log scope narrow.
 *
 * <p>{@link LogCaptureAppender} is attached to the {@code PeeGeeQManager} logger in
 * {@code @BeforeEach} and detached in {@code @AfterEach}. It is cleared inside each test
 * immediately before the scenario under observation begins, so every WARN/ERROR event
 * captured after the clear is guaranteed to originate from that scenario.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PeeGeeQManagerTimerGuardTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManagerTimerGuardTest.class);
    /** Shared container used for tests that need the DB alive throughout. */
    @Container
    static PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private LogCaptureAppender logCapture;
    private ch.qos.logback.classic.Logger managerLogger;

    @BeforeAll
    static void initDb() {
        initializeSchemaFor(postgres);
    }

    @BeforeEach
    void setUp() {
        managerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PeeGeeQManager.class);
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
                .onSuccess(v -> {
                    clearSystemProperties();
                    testContext.completeNow();
                })
                .onFailure(t -> {
                    logger.error("Error closing manager in tearDown", t);
                    clearSystemProperties();
                    testContext.failNow(t);
                });
        } else {
            clearSystemProperties();
            testContext.completeNow();
        }
    }

    // 
    // Test 1: clean close with fast timers  no failure logs
    // 

    @Test
    @DisplayName("No timer failure logs when DB is alive and manager closes cleanly")
    void testNoTimerFailuresDuringCleanClose(VertxTestContext testContext) {
        Properties props = setSystemPropertiesFor(postgres);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", props), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                // Let at least one timer tick fire successfully so we know the timers are running
                .compose(v -> delay(vertx, 1500))
                .compose(v -> {
                    logCapture.clear();
                    // closeReactive() sets closing=true as its very first action.
                    // Any timer tick that fires after that point must return immediately
                    // due to the "if (closing) return;" guard.
                    Future<Void> close = manager.closeReactive();
                    manager = null; // prevent tearDown double-close
                    return close;
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    List<ILoggingEvent> timerFailures = captureTimerFailures();
                    assertTrue(timerFailures.isEmpty(),
                            "No timer failure logs expected during clean close; " +
                            "the 'if (closing) return;' guard must prevent new DB calls. Got: " +
                            timerFailures.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                })));
    }

    // 
    // Test 2: DB goes away  first failures WARN, then escalate to ERROR
    // 

    @Test
    @ExpectedErrorLog(
            logger = "dev.mars.peegeeq.db.PeeGeeQManager",
            message = "Queue depth cache refresh is still failing (3 consecutive failures, 3 total failures):",
            messageMatch = ExpectedErrorLog.MessageMatch.PREFIX,
            throwable = ExpectedErrorLog.ThrowablePolicy.NONE)
    @DisplayName("Timer failures are rate-limited and surfaced through component health")
    void testTimerFailuresEscalateWarnToError(VertxTestContext testContext) {
        // Own container so we can stop it without breaking other tests
        PostgreSQLContainer ownContainer = PostgreSQLTestConstants.createStandardContainer();
        ownContainer.start();
        initializeSchemaFor(ownContainer);

        Properties ownContainerProps = setSystemPropertiesFor(ownContainer);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", ownContainerProps), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    return vertx.executeBlocking(() -> {
                        ownContainer.stop();
                        return (Void) null;
                    });
                })
                .compose(v -> {
                    // The DB is now stopped; all subsequent timer ticks will fail.
                    return awaitDepthCacheUnhealthy(vertx, System.currentTimeMillis() + 8_000);
                })
                .compose(depthHealth -> {
                    testContext.verify(() -> {
                    List<ILoggingEvent> warns = logCapture.eventsAtLevel(Level.WARN);
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);

                    // The first failure retains the complete network-failure stack.
                    //
                    // On Windows, two distinct paths occur:
                    //   1st tick: java.io.IOException ("An established connection was aborted by the
                    //             software in your host machine")  Netty reports the force-killed open
                    //             socket as a plain IOException, not a SocketException.
                    //   Subsequent: io.netty.channel.AbstractChannel$AnnotatedConnectException wrapping
                    //             java.net.ConnectException ("Connection refused")  new connection
                    //             attempts fail because the port is gone.
                    // hasCauseOfAnyType checks for any of the three by exact class name.
                    assertFalse(warns.isEmpty(), "Expected the first timer failure at WARN");
                    assertFalse(errors.isEmpty(),
                            "Expected a rate-limited ERROR summary after the escalation threshold");

                    boolean allWarnsHaveConnectionFailure = warns.stream()
                            .allMatch(e -> hasCauseOfAnyType(e.getThrowableProxy(),
                                    "java.io.IOException",
                                    "java.net.SocketException",
                                    "java.net.ConnectException"));
                    assertTrue(allWarnsHaveConnectionFailure,
                            "Every WARN must carry a network I/O exception (IOException/SocketException/ConnectException) " +
                            "in cause chain  proves the DB-stopped scenario produced these failures and the exception " +
                            "was not swallowed. WARN count: " + warns.size());

                    boolean hasEscalatedError = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("3 consecutive failures"));
                    assertTrue(hasEscalatedError,
                            "The threshold summary must carry the consecutive-failure count. " +
                            "ERROR messages: " + errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    assertTrue(errors.stream().allMatch(e -> e.getThrowableProxy() == null),
                            "Persistent summaries must not repeat the complete stack trace");

                    assertEquals(ComponentHealthState.UNHEALTHY, depthHealth.state());
                    assertTrue(((Number) depthHealth.details().get("consecutiveFailures")).longValue() >= 3,
                            "Health details must expose the current consecutive-failure count");
                    });

                    PeeGeeQManager closingManager = manager;
                    manager = null;
                    return closingManager.closeReactive();
                })
                .onComplete(testContext.succeeding(v -> testContext.completeNow()));

    }

    // 
    // Test 3: closing flag verified race condition protection
    // 

    @Test
    @DisplayName("Closing guard prevents timer callbacks firing after close when DB is alive")
    void testClosingGuardPreventsTimerCallbacksAfterClose(VertxTestContext testContext) {
        // This test specifically protects against the race described in the issue:
        //
        //   "timer fires  refreshDepthCache() future starts  cancelTimer() runs 
        //    pool closes  in-progress future hits 'connection refused'"
        //
        // Without "if (closing) return;", any tick that fires in the window between
        // closeReactive() being called and cancelTimer() completing would attempt a
        // DB operation. With fast 1 s timers there are more such opportunities.
        //
        // With the guard: any tick that fires after closing=true exits immediately.
        // The observable consequence: no "Failed to refresh depth cache" WARN/ERROR.
        Properties props = setSystemPropertiesFor(postgres);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", props), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                // Let a tick or two fire successfully proves the timer is running
                .compose(v -> delay(vertx, 2500))
                .compose(v -> {
                    logCapture.clear();
                    // Close now sets closing=true immediately (before any cancelTimer call).
                    // Timers still registered; fast 1 s interval means a tick may fire between
                    // closing=true and cancelTimer(). The guard must catch it.
                    Future<Void> close = manager.closeReactive();
                    manager = null;
                    return close;
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    // With the guard in place: any tick that fires after closing=true returns
                    // before calling refreshDepthCache() or persistMetrics(). No failure log.
                    List<ILoggingEvent> timerFailures = captureTimerFailures();
                    assertTrue(timerFailures.isEmpty(),
                            "'if (closing) return;' guard must stop timer callbacks from " +
                            "attempting DB calls after closeReactive() is invoked. " +
                            "Unexpected failure logs: " +
                            timerFailures.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("In-flight background tasks fail fast if manager is closed")
    void testInFlightTasksFailFast(VertxTestContext testContext) {
        Properties props = setSystemPropertiesFor(postgres);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", props), new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    // Close the manager, setting closing=true immediately.
                    Future<Void> close = manager.closeReactive();
                    
                    // Immediately invoke background tasks simulating a race condition
                    // where the timer fired right before/during close but the task 
                    // execution started just after closing=true was set.
                    // persistMetrics was deleted 2026-08-09 (write-only queue_metrics
                    // persistence); the depth-cache refresh is the remaining DB-touching task
                    // this race guard exists for.
                    Future<Void> depthCache = manager.getMetrics().refreshDepthCache();

                    manager = null;
                    return close.compose(v2 -> depthCache)
                                .transform(ar -> Future.succeededFuture());
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    // With the fail-fast guard inside the tasks, no connection 
                    // refused errors should be logged.
                    List<ILoggingEvent> timerFailures = captureTimerFailures();
                    assertTrue(timerFailures.isEmpty(),
                            "Background tasks should fail-fast without hitting DB when closing. Got: " +
                            timerFailures.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Fast timers with immediate close do not cause connection refused errors")
    void testFastTimersWithImmediateClose(VertxTestContext testContext) {
        // This test uses very fast timer intervals (100ms) to maximize the likelihood
        // of a timer callback firing during the close sequence, verifying that the
        // markClosing() defense prevents connection refused errors.
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host", postgres.getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        props.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        props.setProperty("peegeeq.database.username", postgres.getUsername());
        props.setProperty("peegeeq.database.password", postgres.getPassword());
        props.setProperty("peegeeq.database.ssl.enabled", "false");
        props.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);
        props.setProperty("peegeeq.database.pool.min-size", "1");
        props.setProperty("peegeeq.database.pool.max-size", "3");
        props.setProperty("peegeeq.database.pool.shared", "false");
        props.setProperty("peegeeq.health-check.interval", "PT30S");

        // VERY fast timer intervals - PT1S is minimum, but we verify close happens quickly
        props.setProperty("peegeeq.metrics.enabled", "true");
        props.setProperty("peegeeq.metrics.reporting-interval", "PT1S");
        props.setProperty("peegeeq.metrics.depth-cache-interval", "PT1S");

        // Disable other background tasks
        props.setProperty("peegeeq.queue.recovery.enabled", "false");
        props.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        props.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");
        props.setProperty("peegeeq.migration.enabled", "false");
        props.setProperty("peegeeq.migration.auto-migrate", "false");

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", props), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                // Let 2-3 timer ticks fire successfully to ensure timers are running
                .compose(v -> delay(vertx, 2500))
                .compose(v -> {
                    logCapture.clear();
                    // Close immediately - with 1s intervals, there's a good chance
                    // a timer is about to fire or is firing right now.
                    // The markClosing() calls should prevent any connection refused errors.
                    Future<Void> close = manager.closeReactive();
                    manager = null;
                    return close;
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    List<ILoggingEvent> timerFailures = captureTimerFailures();
                    assertTrue(timerFailures.isEmpty(),
                            "Fast timers with immediate close should not cause connection refused errors. " +
                            "The markClosing() defense should prevent database queries after shutdown begins. Got: " +
                            timerFailures.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                })));
    }

    // 
    // Helpers
    // 

    /**
     * Returns true if the cause chain contains any of the given class names.
     * Used to handle platform-specific exception wrapping: on Windows, a force-killed
     * container produces java.io.IOException ("connection aborted") on the open socket,
     * while subsequent attempts produce io.netty.channel.AbstractChannel$AnnotatedConnectException
     * wrapping java.net.ConnectException ("connection refused"). Both are network failures;
     * both satisfy the intent of the assertion.
     */
    private boolean hasCauseOfAnyType(ch.qos.logback.classic.spi.IThrowableProxy proxy, String... classNames) {
        java.util.Set<String> names = java.util.Set.of(classNames);
        while (proxy != null) {
            if (names.contains(proxy.getClassName())) return true;
            proxy = proxy.getCause();
        }
        return false;
    }

    /**
     * Returns all WARN/ERROR events captured from PeeGeeQManager since the last logCapture.clear().
     * No message-string filtering: logCapture is scoped to PeeGeeQManager and cleared before each
     * scenario, so any WARN or ERROR after the clear is a timer failure regardless of message text.
     */
    private List<ILoggingEvent> captureTimerFailures() {
        return logCapture.snapshot().stream()
                .filter(e -> e.getLevel().equals(Level.WARN) || e.getLevel().equals(Level.ERROR))
                .toList();
    }

    private Future<Void> delay(Vertx vertx, long ms) {
        return vertx.timer(ms).mapEmpty();
    }

    private Future<HealthStatusInfo> awaitDepthCacheUnhealthy(Vertx vertx, long deadlineMs) {
        HealthStatusInfo status = manager.getHealthCheckManager()
                .getComponentHealth("background-depth-cache");
        if (status != null && status.state() == ComponentHealthState.UNHEALTHY) {
            return Future.succeededFuture(status);
        }
        if (System.currentTimeMillis() >= deadlineMs) {
            return Future.failedFuture(new AssertionError(
                    "Depth-cache health did not become UNHEALTHY before deadline; last status=" + status));
        }
        return vertx.timer(100).compose(ignored -> awaitDepthCacheUnhealthy(vertx, deadlineMs));
    }

    private Properties setSystemPropertiesFor(PostgreSQLContainer container) {
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host", container.getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(container.getFirstMappedPort()));
        props.setProperty("peegeeq.database.name", container.getDatabaseName());
        props.setProperty("peegeeq.database.username", container.getUsername());
        props.setProperty("peegeeq.database.password", container.getPassword());
        props.setProperty("peegeeq.database.ssl.enabled", "false");
        props.setProperty("peegeeq.database.schema", PostgreSQLTestConstants.TEST_SCHEMA);
        props.setProperty("peegeeq.database.pool.min-size", "1");
        props.setProperty("peegeeq.database.pool.max-size", "3");
        props.setProperty("peegeeq.database.pool.shared", "false");
        props.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        props.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        props.setProperty("peegeeq.health-check.interval", "PT1S");
        props.setProperty("peegeeq.health-check.timeout", "PT1S");

        // Fast timer intervals minimum allowed by configuration validation
        props.setProperty("peegeeq.metrics.enabled", "true");
        props.setProperty("peegeeq.metrics.reporting-interval", "PT1S");
        props.setProperty("peegeeq.metrics.depth-cache-interval", "PT1S");

        // Disable slow/complex background tasks focus the test on the 2 fast timers only
        props.setProperty("peegeeq.queue.recovery.enabled", "false");
        props.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        props.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");

        props.setProperty("peegeeq.migration.enabled", "false");
        props.setProperty("peegeeq.migration.auto-migrate", "false");
        return props;
    }

    private void clearSystemProperties() {
        // no-op: System properties are no longer written by this test (uses 2-arg constructor)
    }

    private static void initializeSchemaFor(PostgreSQLContainer container) {
        PeeGeeQTestSchemaInitializer.initializeSchema(
                container, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
    }

    // 
    // Log capture
    // 

    /**
     * Logback appender that collects {@link ILoggingEvent} instances from the
     * {@code PeeGeeQManager} logger into an in-memory list.
     *
     * <p>Scoping contract: the appender is attached only to the {@code PeeGeeQManager} logger,
     * so every event it records originates from that class. Each test calls {@link #clear()}
     * immediately before the scenario under observation, ensuring that assertions on
     * {@link #snapshot()} or {@link #eventsAtLevel(Level)} see only events from that scenario.
     */
    static final class LogCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }

        List<ILoggingEvent> snapshot() {
            return new ArrayList<>(events);
        }

        List<ILoggingEvent> eventsAtLevel(Level level) {
            // Stream over the snapshot copy, never over the live list: synchronizedList
            // guards single calls only, so streaming it races the appender thread
            // (ConcurrentModificationException seen 2026-08-10). snapshot() copies via
            // one synchronized toArray call.
            return snapshot().stream()
                    .filter(e -> e.getLevel().equals(level))
                    .toList();
        }

        void clear() {
            events.clear();
        }
    }
}
