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
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *       when the DB is stopped while the manager is running, the first 1–2 timer ticks log at
 *       WARN; from the 3rd consecutive failure onward they escalate to ERROR with an
 *       "(N consecutive failures)" count in the message. Every captured log event must carry
 *       {@link java.net.ConnectException} in its cause chain, proving the DB-stopped scenario
 *       is what produced the failures (not a swallowed exception or an unrelated error).</li>
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
    private static final String POSTGRES_IMAGE = PgTestImageConstant.POSTGRES_IMAGE;

    /** Shared container used for tests that need the DB alive throughout. */
    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

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

    // ─────────────────────────────────────────────────────────────────
    // Test 1: clean close with fast timers → no failure logs
    // ─────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────
    // Test 2: DB goes away → first failures WARN, then escalate to ERROR
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Timer failures escalate from WARN to ERROR after consecutive-failure threshold")
    void testTimerFailuresEscalateWarnToError(VertxTestContext testContext) {
        // Own container so we can stop it without breaking other tests
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withReuse(false);
        ownContainer.start();
        initializeSchemaFor(ownContainer);

        Properties ownContainerProps = setSystemPropertiesFor(ownContainer);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test", ownContainerProps), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                .compose(v -> {
                    // Kill the DB — all subsequent timer ticks will fail
                    ownContainer.stop();
                    logCapture.clear();
                    logger.error("===== INTENTIONAL ERROR TEST BEGIN ===== DB container stopped." +
                                 " 'Failed to refresh depth cache' WARN/ERROR logs below are EXPECTED" +
                                 " for the next ~3 seconds. See END marker when they stop.");
                    // Wait for 3 timer ticks at 1 s interval → 3 consecutive failures
                    // Expected: ticks 1–2 → WARN, tick 3 → ERROR (escalation threshold)
                    return delay(vertx, 3500);
                })
                .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                    logger.error("===== INTENTIONAL ERROR TEST END ===== Expected 'Failed to refresh" +
                                 " depth cache' WARN/ERROR sequence is complete. Asserting captured logs.");
                    List<ILoggingEvent> warns = logCapture.eventsAtLevel(Level.WARN);
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);

                    // PRIMARY: every captured event must carry ConnectException in the cause chain.
                    // This proves the DB-stopped scenario is what produced these failures — not an
                    // NPE, misconfiguration, or any other unrelated error.
                    // If PeeGeeQManager swallowed the exception (logged only e.getMessage()),
                    // getThrowableProxy() returns null and this fails immediately.
                    assertFalse(warns.isEmpty(),
                            "Expected WARN events from timer failures after container stop; none captured");
                    assertFalse(errors.isEmpty(),
                            "Expected ERROR events after escalation threshold; none captured");

                    boolean allWarnsHaveConnectException = warns.stream()
                            .allMatch(e -> hasCauseOfType(e.getThrowableProxy(), "java.net.ConnectException"));
                    assertTrue(allWarnsHaveConnectException,
                            "Every WARN must carry ConnectException in cause chain — proves the DB-stopped " +
                            "scenario produced these failures and the exception was not swallowed. " +
                            "WARN count: " + warns.size());

                    boolean allErrorsHaveConnectException = errors.stream()
                            .allMatch(e -> hasCauseOfType(e.getThrowableProxy(), "java.net.ConnectException"));
                    assertTrue(allErrorsHaveConnectException,
                            "Every ERROR must carry ConnectException in cause chain — proves the DB-stopped " +
                            "scenario produced these failures and the exception was not swallowed. " +
                            "ERROR count: " + errors.size());

                    // SECONDARY: escalation behaviour — first failures at WARN, then ERROR with count
                    boolean hasEarlyWarn = warns.stream()
                            .anyMatch(e -> !e.getFormattedMessage().contains("consecutive failures"));
                    assertTrue(hasEarlyWarn,
                            "Initial failures must be logged at WARN without '(N consecutive failures)'. " +
                            "WARN messages: " + warns.stream().map(ILoggingEvent::getFormattedMessage).toList());

                    boolean hasEscalatedError = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("(3 consecutive") ||
                                          e.getFormattedMessage().contains("(4 consecutive") ||
                                          e.getFormattedMessage().contains("(5 consecutive"));
                    assertTrue(hasEscalatedError,
                            "At/beyond threshold (3), failures must be ERROR with '(N consecutive failures)'. " +
                            "ERROR messages: " + errors.stream().map(ILoggingEvent::getFormattedMessage).toList());

                    // Close the manager before test completes so tearDown does not try to
                    // close it against a stopped container (which would cause tearDown to failNow).
                    // ownContainer is already stopped; closeReactive() cancels timers regardless.
                    PeeGeeQManager closingManager = manager;
                    manager = null; // prevent tearDown double-close
                    closingManager.closeReactive()
                            .eventually(() -> {
                                testContext.completeNow();
                                return Future.succeededFuture();
                            });
                })));

    }

    // ─────────────────────────────────────────────────────────────────
    // Test 3: closing flag verified race condition protection
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Closing guard prevents timer callbacks firing after close when DB is alive")
    void testClosingGuardPreventsTimerCallbacksAfterClose(VertxTestContext testContext) {
        // This test specifically protects against the race described in the issue:
        //
        //   "timer fires → refreshDepthCache() future starts → cancelTimer() runs →
        //    pool closes → in-progress future hits 'connection refused'"
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
                    Future<Void> depthCache = manager.getMetrics().refreshDepthCache();
                    Future<Void> persist = manager.getMetrics().persistMetrics(manager.getMeterRegistry());
                    
                    manager = null;
                    return close.compose(v2 -> Future.join(depthCache, persist))
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
        props.setProperty("peegeeq.database.schema", "public");
        props.setProperty("peegeeq.database.pool.min-size", "1");
        props.setProperty("peegeeq.database.pool.max-size", "3");
        props.setProperty("peegeeq.database.pool.shared", "false");
        props.setProperty("peegeeq.health.check-interval", "PT30S");

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

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private boolean hasCauseOfType(ch.qos.logback.classic.spi.IThrowableProxy proxy, String className) {
        while (proxy != null) {
            if (className.equals(proxy.getClassName())) return true;
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
        Promise<Void> p = Promise.promise();
        vertx.setTimer(ms, id -> p.complete());
        return p.future();
    }

    private Properties setSystemPropertiesFor(PostgreSQLContainer container) {
        Properties props = new Properties();
        props.setProperty("peegeeq.database.host", container.getHost());
        props.setProperty("peegeeq.database.port", String.valueOf(container.getFirstMappedPort()));
        props.setProperty("peegeeq.database.name", container.getDatabaseName());
        props.setProperty("peegeeq.database.username", container.getUsername());
        props.setProperty("peegeeq.database.password", container.getPassword());
        props.setProperty("peegeeq.database.ssl.enabled", "false");
        props.setProperty("peegeeq.database.schema", "public");
        props.setProperty("peegeeq.database.pool.min-size", "1");
        props.setProperty("peegeeq.database.pool.max-size", "3");
        props.setProperty("peegeeq.database.pool.shared", "false");
        props.setProperty("peegeeq.database.pool.idle-timeout-ms", "2000");
        props.setProperty("peegeeq.database.pool.connection-timeout-ms", "5000");
        props.setProperty("peegeeq.health.check-interval", "PT30S");

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

    @SuppressWarnings("unchecked")
    private static void initializeSchemaFor(PostgreSQLContainer container) {
        try (Connection conn = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbox (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    processed_at TIMESTAMP WITH TIME ZONE,
                    processing_started_at TIMESTAMP WITH TIME ZONE,
                    status VARCHAR(50) DEFAULT 'PENDING',
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    next_retry_at TIMESTAMP WITH TIME ZONE,
                    version INT DEFAULT 0,
                    headers JSONB DEFAULT '{}',
                    error_message TEXT,
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5,
                    required_consumer_groups INT DEFAULT 1,
                    completed_consumer_groups INT DEFAULT 0,
                    completed_groups_bitmap BIGINT DEFAULT 0
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_messages (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    visible_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    lock_id BIGINT,
                    lock_until TIMESTAMP WITH TIME ZONE,
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    status VARCHAR(50) DEFAULT 'AVAILABLE',
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255),
                    priority INT DEFAULT 5
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS dead_letter_queue (
                    id BIGSERIAL PRIMARY KEY,
                    original_table VARCHAR(50) NOT NULL,
                    original_id BIGINT NOT NULL,
                    topic VARCHAR(255) NOT NULL,
                    payload JSONB NOT NULL,
                    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    failed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    failure_reason TEXT NOT NULL,
                    retry_count INT NOT NULL,
                    headers JSONB DEFAULT '{}',
                    correlation_id VARCHAR(255),
                    message_group VARCHAR(255)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_metrics (
                    id BIGSERIAL PRIMARY KEY,
                    metric_name VARCHAR(100) NOT NULL,
                    metric_value DOUBLE PRECISION NOT NULL,
                    tags JSONB DEFAULT '{}',
                    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbox_topic_subscriptions (
                    id BIGSERIAL PRIMARY KEY,
                    topic VARCHAR(255) NOT NULL,
                    group_name VARCHAR(255) NOT NULL,
                    subscription_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    subscribed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    last_active_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    start_from_message_id BIGINT,
                    start_from_timestamp TIMESTAMP WITH TIME ZONE,
                    heartbeat_interval_seconds INT NOT NULL DEFAULT 60,
                    heartbeat_timeout_seconds INT NOT NULL DEFAULT 300,
                    last_heartbeat_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    backfill_status VARCHAR(20) DEFAULT 'NONE',
                    backfill_checkpoint_id BIGINT,
                    backfill_processed_messages BIGINT DEFAULT 0,
                    backfill_total_messages BIGINT,
                    backfill_started_at TIMESTAMP WITH TIME ZONE,
                    backfill_completed_at TIMESTAMP WITH TIME ZONE,
                    consecutive_misses INTEGER NOT NULL DEFAULT 0,
                    dead_after_misses INTEGER NOT NULL DEFAULT 3,
                    UNIQUE (topic, group_name)
                )
                """);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Log capture
    // ─────────────────────────────────────────────────────────────────

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
            return events.stream()
                    .filter(e -> e.getLevel().equals(level))
                    .toList();
        }

        void clear() {
            events.clear();
        }
    }
}
