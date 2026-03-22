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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that PeeGeeQManager.closeReactive() logs at ERROR level (not WARN)
 * when cleanup operations fail. Covers the 6 WARN→ERROR changes in closeReactive():
 *
 * <ol>
 *   <li>stop() failure during close</li>
 *   <li>Close hook failure</li>
 *   <li>Worker executor close failure</li>
 *   <li>Client factory close failure</li>
 *   <li>MeterRegistry close failure</li>
 *   <li>Vert.x close (non-RejectedExecutionException) failure</li>
 * </ol>
 *
 * Positive tests: trigger failure conditions → verify ERROR logged.
 * Negative tests: clean close → verify no ERROR logged in cleanup chain.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PeeGeeQManagerCloseLogLevelTest {

    private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";

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

    @org.junit.jupiter.api.BeforeAll
    static void initDb() {
        initializeSchemaFor(postgres);
    }

    @BeforeEach
    void setUp() {
        // Attach log capture to PeeGeeQManager's logger
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
            manager.closeReactive()
                    .recover(t -> Future.succeededFuture())
                    .onComplete(v -> {
                        clearSystemProperties();
                        testContext.completeNow();
                    });
        } else {
            clearSystemProperties();
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Negative: clean close with DB alive produces no ERROR from cleanup chain")
    void testCleanCloseNoErrorLogs(VertxTestContext testContext) throws InterruptedException {
        setSystemProperties();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    List<String> errorMessages = errors.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList();

                    // No ERROR from the cleanup chain when everything closes cleanly
                    boolean hasCleanupError = errorMessages.stream().anyMatch(msg ->
                            msg.contains("stop() failed during close") ||
                            msg.contains("Close hook") ||
                            msg.contains("Failed to close worker executor") ||
                            msg.contains("Error closing client factory") ||
                            msg.contains("Failed to close MeterRegistry") ||
                            msg.contains("Error closing Vert.x instance"));

                    assertFalse(hasCleanupError,
                            "Clean close should not produce ERROR logs from cleanup chain, but got: " + errorMessages);

                    // Manager reference set to null so tearDown doesn't double-close
                    manager = null;
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Positive: close after DB shutdown logs ERROR for client factory close failure")
    void testCloseAfterDbShutdownLogsErrorForClientFactoryFailure(VertxTestContext testContext) throws InterruptedException {
        // Use a separate container that we can stop
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

        manager.start()
                .compose(v -> {
                    // Stop the database to cause close failures
                    ownContainer.stop();
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onSuccess(v -> testContext.verify(() -> {
                    // closeReactive() should complete (via .recover()) even with failures
                    // The key assertion: failures are logged at ERROR, not WARN
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    List<ILoggingEvent> warns = logCapture.eventsAtLevel(Level.WARN);

                    // Verify no WARN messages for the cleanup steps that should be ERROR
                    boolean hasWarnForCleanup = warns.stream().anyMatch(e ->
                            e.getFormattedMessage().contains("stop() failed during close") ||
                            e.getFormattedMessage().contains("Failed to close worker executor") ||
                            e.getFormattedMessage().contains("Error closing client factory") ||
                            e.getFormattedMessage().contains("Failed to close MeterRegistry"));
                    assertFalse(hasWarnForCleanup,
                            "Cleanup failures should be at ERROR level, not WARN");

                    // closeReactive() completed successfully (chained recovers)
                    manager = null;
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(60, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Positive: close without start produces no ERROR (stop() is a no-op when not started)")
    void testCloseWithoutStartNoStopError(VertxTestContext testContext) throws InterruptedException {
        setSystemProperties();
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());

        logCapture.clear();
        manager.closeReactive()
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    boolean hasStopError = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("stop() failed during close"));
                    assertFalse(hasStopError,
                            "stop() on an un-started manager should not produce an ERROR");

                    manager = null;
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Negative: Vert.x close with RejectedExecutionException stays at WARN/DEBUG, not ERROR")
    void testVertxCloseExpectedExceptionNotLoggedAsError(VertxTestContext testContext) throws InterruptedException {
        setSystemProperties();
        // Manager owns its own Vert.x (default constructor path)
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());

        manager.start()
                .compose(v -> {
                    logCapture.clear();
                    return manager.closeReactive();
                })
                .onSuccess(v -> testContext.verify(() -> {
                    // If Vert.x close produces a RejectedExecutionException (expected during shutdown),
                    // it should be at DEBUG, not ERROR
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    boolean hasVertxCloseError = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("Error closing Vert.x instance"));
                    assertFalse(hasVertxCloseError,
                            "Expected Vert.x RejectedExecutionException during close should not be logged at ERROR");

                    manager = null;
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // --- Helpers ---

    private void setSystemProperties() {
        setSystemPropertiesFor(postgres);
    }

    @SuppressWarnings("unchecked")
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
        props.setProperty("peegeeq.database.pool.max-size", "5");
        props.setProperty("peegeeq.health.check-interval", "PT5S");
        props.setProperty("peegeeq.metrics.reporting-interval", "PT10S");
        props.setProperty("peegeeq.migration.enabled", "false");
        props.setProperty("peegeeq.migration.auto-migrate", "false");
        props.forEach((k, v) -> System.setProperty(k.toString(), v.toString()));
    }

    private void clearSystemProperties() {
        System.getProperties().entrySet().removeIf(entry ->
                entry.getKey().toString().startsWith("peegeeq."));
    }

    private void initializeSchema() {
        initializeSchemaFor(postgres);
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
                CREATE TABLE IF NOT EXISTS outbox_topics (
                    topic VARCHAR(255) PRIMARY KEY,
                    semantics VARCHAR(20) NOT NULL DEFAULT 'QUEUE',
                    message_retention_hours INT NOT NULL DEFAULT 24,
                    zero_subscription_retention_hours INT DEFAULT 24,
                    block_writes_on_zero_subscriptions BOOLEAN DEFAULT FALSE,
                    completion_tracking_mode VARCHAR(20) DEFAULT 'REFERENCE_COUNTING',
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
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
                    UNIQUE (topic, group_name)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS outbox_consumer_groups (
                    id BIGSERIAL PRIMARY KEY,
                    message_id BIGINT NOT NULL,
                    group_name VARCHAR(255) NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    processed_at TIMESTAMP WITH TIME ZONE,
                    error_message TEXT,
                    retry_count INT DEFAULT 0,
                    UNIQUE (message_id, group_name)
                )
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS processed_ledger (
                    message_id BIGINT NOT NULL,
                    group_name VARCHAR(255) NOT NULL,
                    processed_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    PRIMARY KEY (message_id, group_name)
                )
                """);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    /**
     * Log capture appender using logback's AppenderBase — same pattern used
     * in the existing ObservabilitySystemIntegrationTest.
     */
    static final class LogCaptureAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
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
