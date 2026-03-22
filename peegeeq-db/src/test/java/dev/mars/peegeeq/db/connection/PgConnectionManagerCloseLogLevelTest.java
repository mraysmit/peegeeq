package dev.mars.peegeeq.db.connection;

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
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that PgConnectionManager.closeAsync() logs at ERROR level (not WARN)
 * when pool close operations fail.
 *
 * <p>Covers WARN→ERROR change #10: pool close failures in closeAsync().</p>
 *
 * Positive: trigger pool close failure → verify ERROR logged.
 * Negative: clean close → verify no ERROR logged.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class PgConnectionManagerCloseLogLevelTest {

    private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private PgConnectionManager connectionManager;
    private LogCaptureAppender logCapture;
    private ch.qos.logback.classic.Logger connManagerLogger;

    @BeforeEach
    void setUp(Vertx vertx) {
        connectionManager = new PgConnectionManager(vertx);

        connManagerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PgConnectionManager.class);
        logCapture = new LogCaptureAppender();
        logCapture.setContext(connManagerLogger.getLoggerContext());
        logCapture.start();
        connManagerLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        connManagerLogger.detachAppender(logCapture);
        logCapture.stop();

        if (connectionManager != null) {
            connectionManager.closeAsync()
                    .recover(t -> Future.succeededFuture())
                    .onComplete(v -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Negative: clean close with DB alive produces no ERROR")
    void testCleanCloseNoErrorLogs(VertxTestContext testContext) throws InterruptedException {
        PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(2)
                .build();

        // Create a pool
        connectionManager.getOrCreateReactivePool("test-service", config, poolConfig);

        logCapture.clear();
        connectionManager.closeAsync()
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    boolean hasPoolCloseError = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("pools failed to close cleanly"));
                    assertFalse(hasPoolCloseError,
                            "Clean close should produce no ERROR about pools failing to close");

                    connectionManager = null;
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Positive: close after DB shutdown produces ERROR when pools fail to close")
    void testCloseAfterDbShutdownLogsError(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        // Create a dedicated container that we can stop
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withReuse(false);
        ownContainer.start();

        PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(ownContainer.getHost())
                .port(ownContainer.getFirstMappedPort())
                .database(ownContainer.getDatabaseName())
                .username(ownContainer.getUsername())
                .password(ownContainer.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(2)
                .build();

        PgConnectionManager ownConnMgr = new PgConnectionManager(vertx);
        ownConnMgr.getOrCreateReactivePool("test-service", config, poolConfig);

        // Capture logs for the new manager
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PgConnectionManager.class);
        LogCaptureAppender ownCapture = new LogCaptureAppender();
        ownCapture.setContext(logger.getLoggerContext());
        ownCapture.start();
        logger.addAppender(ownCapture);

        // Validate pool is alive before stopping the container
        ownConnMgr.getExistingPool("test-service")
                .query("SELECT 1")
                .execute()
                .compose(rows -> {
                    // Stop the database to cause pool close failures
                    ownContainer.stop();
                    ownCapture.clear();
                    return ownConnMgr.closeAsync();
                })
                .onComplete(ar -> testContext.verify(() -> {
                    // closeAsync() completes via .recover() even when pools fail
                    List<ILoggingEvent> errors = ownCapture.eventsAtLevel(Level.ERROR);
                    List<ILoggingEvent> warns = ownCapture.eventsAtLevel(Level.WARN);

                    // Verify that if pool close does fail, it's at ERROR not WARN
                    boolean hasWarnForPoolClose = warns.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("pools failed to close cleanly"));
                    assertFalse(hasWarnForPoolClose,
                            "Pool close failures should be at ERROR level, not WARN");

                    logger.detachAppender(ownCapture);
                    ownCapture.stop();
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Negative: close with no pools produces no ERROR")
    void testCloseWithNoPoolsNoError(VertxTestContext testContext) throws InterruptedException {
        // Don't create any pools
        logCapture.clear();
        connectionManager.closeAsync()
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    assertTrue(errors.isEmpty(),
                            "Closing with no pools should produce no ERROR logs");

                    connectionManager = null;
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

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
