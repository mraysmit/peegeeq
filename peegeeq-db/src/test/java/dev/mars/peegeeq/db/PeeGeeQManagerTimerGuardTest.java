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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that protect against the timer callback race conditions fixed in PeeGeeQManager:
 *
 * <ol>
 *   <li><b>Closing guard</b>: When {@code closing=true}, timer callbacks must return immediately
 *       without invoking any DB operation. Without the guard, a timer that fires between
 *       {@code closing=true} and {@code cancelTimer()} would attempt {@code refreshDepthCache()}
 *       against a pool that is about to close, producing a spurious "connection refused" WARN.</li>
 *   <li><b>Consecutive failure escalation</b>: First 1–2 timer failures log at WARN.
 *       From the 3rd consecutive failure onward, they escalate to ERROR with a
 *       "(N consecutive failures)" count. The counter resets on success.</li>
 * </ol>
 *
 * <p>Strategy: set {@code peegeeq.metrics.depth-cache-interval=PT1S} (the minimum) so timer
 * ticks fire fast enough to observe multiple failures in a few seconds.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PeeGeeQManagerTimerGuardTest {

    private static final Logger logger = LoggerFactory.getLogger(PeeGeeQManagerTimerGuardTest.class);
    private static final String POSTGRES_IMAGE = PgTestImageConstant.POSTGRES_IMAGE;

    /** Shared container — used for tests that need the DB alive throughout. */
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
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        managerLogger.detachAppender(logCapture);
        logCapture.stop();
        if (manager != null) {
            manager.closeReactive().onComplete(v -> {
                clearSystemProperties();
                testContext.completeNow();
            });
        } else {
            clearSystemProperties();
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // ─────────────────────────────────────────────────────────────────
    // Test 1: clean close with fast timers → no failure logs
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("No timer failure logs when DB is alive and manager closes cleanly")
    void testNoTimerFailuresDuringCleanClose(VertxTestContext testContext) throws InterruptedException {
        setSystemPropertiesFor(postgres);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
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
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> timerFailures = captureTimerFailures();
                    assertTrue(timerFailures.isEmpty(),
                            "No timer failure logs expected during clean close; " +
                            "the 'if (closing) return;' guard must prevent new DB calls. Got: " +
                            timerFailures.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // ─────────────────────────────────────────────────────────────────
    // Test 2: DB goes away → first failures WARN, then escalate to ERROR
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Timer failures escalate from WARN to ERROR after consecutive-failure threshold")
    void testTimerFailuresEscalateWarnToError(VertxTestContext testContext) throws InterruptedException {
        logger.error("===== INTENTIONAL ERROR TEST ===== The next 'Failed to refresh depth cache' " +
                     "ERROR logs are EXPECTED — this test deliberately stops the DB container " +
                     "to verify that consecutive timer failures escalate from WARN to ERROR");

        // Own container so we can stop it without breaking other tests
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withReuse(false);
        ownContainer.start();
        initializeSchemaFor(ownContainer);

        setSystemPropertiesFor(ownContainer);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                .compose(v -> {
                    // Kill the DB — all subsequent timer ticks will fail
                    ownContainer.stop();
                    logCapture.clear();
                    // Wait for 5 timer ticks at 1 s interval → 5 consecutive failures
                    // Expected: ticks 1–2 → WARN, tick 3–5 → ERROR
                    return delay(vertx, 5500);
                })
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> warns = logCapture.eventsAtLevel(Level.WARN);
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);

                    // First 1–2 failures must appear at WARN (no "consecutive failures" label)
                    boolean hasEarlyWarn = warns.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(msg -> msg.contains("Failed to refresh depth cache")
                                         && !msg.contains("consecutive failures"));
                    assertTrue(hasEarlyWarn,
                            "Initial failures should be logged at WARN without 'consecutive failures'. " +
                            "WARN messages: " + warns.stream().map(ILoggingEvent::getFormattedMessage).toList());

                    // 3rd+ failures must appear at ERROR with "(N consecutive failures)"
                    boolean hasEscalatedError = errors.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(msg -> msg.contains("Failed to refresh depth cache")
                                         && msg.contains("consecutive failures"));
                    assertTrue(hasEscalatedError,
                            "Failures at/beyond threshold (3) must be logged at ERROR with " +
                            "'consecutive failures'. ERROR messages: " +
                            errors.stream().map(ILoggingEvent::getFormattedMessage).toList());

                    // The failure count in the ERROR message must be ≥ 3
                    boolean hasThresholdCount = errors.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .anyMatch(msg -> msg.contains("Failed to refresh depth cache") &&
                                    (msg.contains("(3 consecutive") ||
                                     msg.contains("(4 consecutive") ||
                                     msg.contains("(5 consecutive")));
                    assertTrue(hasThresholdCount,
                            "ERROR message must report the failure count (≥ 3). " +
                            "ERROR messages: " + errors.stream().map(ILoggingEvent::getFormattedMessage).toList());

                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // ─────────────────────────────────────────────────────────────────
    // Test 3: closing flag verified — race condition protection
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Closing guard prevents timer callbacks firing after close when DB is alive")
    void testClosingGuardPreventsTimerCallbacksAfterClose(VertxTestContext testContext) throws InterruptedException {
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
        setSystemPropertiesFor(postgres);
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        Vertx vertx = manager.getVertx();

        manager.start()
                // Let a tick or two fire successfully — proves the timer is running
                .compose(v -> delay(vertx, 2500))
                .compose(v -> {
                    logCapture.clear();
                    // Close now — sets closing=true immediately (before any cancelTimer call).
                    // Timers still registered; fast 1 s interval means a tick may fire between
                    // closing=true and cancelTimer(). The guard must catch it.
                    Future<Void> close = manager.closeReactive();
                    manager = null;
                    return close;
                })
                .onSuccess(v -> testContext.verify(() -> {
                    // With the guard in place: any tick that fires after closing=true returns
                    // before calling refreshDepthCache() or persistMetrics(). No failure log.
                    List<ILoggingEvent> timerFailures = captureTimerFailures();
                    assertTrue(timerFailures.isEmpty(),
                            "'if (closing) return;' guard must stop timer callbacks from " +
                            "attempting DB calls after closeReactive() is invoked. " +
                            "Unexpected failure logs: " +
                            timerFailures.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private List<ILoggingEvent> captureTimerFailures() {
        return logCapture.snapshot().stream()
                .filter(e -> e.getLevel().equals(Level.WARN) || e.getLevel().equals(Level.ERROR))
                .filter(e -> {
                    String msg = e.getFormattedMessage();
                    return msg.contains("Failed to refresh depth cache")
                            || msg.contains("Failed to persist metrics")
                            || msg.contains("Failed to recover stuck messages")
                            || msg.contains("Failed to cleanup old dead letter messages");
                })
                .toList();
    }

    private Future<Void> delay(Vertx vertx, long ms) {
        Promise<Void> p = Promise.promise();
        vertx.setTimer(ms, id -> p.complete());
        return p.future();
    }

    private void setSystemPropertiesFor(PostgreSQLContainer container) {
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

        // Fast timer intervals — minimum allowed by configuration validation
        props.setProperty("peegeeq.metrics.enabled", "true");
        props.setProperty("peegeeq.metrics.reporting-interval", "PT1S");
        props.setProperty("peegeeq.metrics.depth-cache-interval", "PT1S");

        // Disable slow/complex background tasks — focus the test on the 2 fast timers only
        props.setProperty("peegeeq.queue.recovery.enabled", "false");
        props.setProperty("peegeeq.queue.dead-consumer-detection.enabled", "false");
        props.setProperty("peegeeq.queue.consumer-group-retry.enabled", "false");

        props.setProperty("peegeeq.migration.enabled", "false");
        props.setProperty("peegeeq.migration.auto-migrate", "false");
        props.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
    }

    private void clearSystemProperties() {
        System.getProperties().entrySet().removeIf(entry ->
                entry.getKey().toString().startsWith("peegeeq."));
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
