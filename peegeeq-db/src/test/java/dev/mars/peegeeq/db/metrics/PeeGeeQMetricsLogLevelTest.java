package dev.mars.peegeeq.db.metrics;

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
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that PeeGeeQMetrics.persistMetrics() logs at ERROR level (not WARN)
 * for non-connection persist failures.
 *
 * <p>Covers WARN→ERROR change #11: non-connection persist failure in persistMetrics().</p>
 *
 * Positive: trigger SQL failure (missing table) → verify ERROR logged.
 * Negative: persist with proper table → verify no ERROR logged.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PeeGeeQMetricsLogLevelTest {

    private static final String POSTGRES_IMAGE = "postgres:15.13-alpine3.20";

    @SuppressWarnings("resource")
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withReuse(false);

    private PgConnectionManager connectionManager;
    private PeeGeeQMetrics metrics;
    private LogCaptureAppender logCapture;
    private ch.qos.logback.classic.Logger metricsLogger;

    @BeforeEach
    void setUp(Vertx vertx) {
        connectionManager = new PgConnectionManager(vertx);

        metricsLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(PeeGeeQMetrics.class);
        logCapture = new LogCaptureAppender();
        logCapture.setContext(metricsLogger.getLoggerContext());
        logCapture.start();
        metricsLogger.addAppender(logCapture);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws InterruptedException {
        metricsLogger.detachAppender(logCapture);
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
    @Order(2)
    @DisplayName("Negative: persistMetrics with queue_metrics table present produces no ERROR")
    void testPersistWithTableNoError(VertxTestContext testContext) throws InterruptedException {
        // Create queue_metrics table
        createMetricsTable();

        Pool pool = createPool();
        metrics = new PeeGeeQMetrics(pool, "test-instance");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        logCapture.clear();
        metrics.persistMetrics(registry)
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    boolean hasPersistError = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("Failed to persist metrics"));
                    assertFalse(hasPersistError,
                            "Persist with valid table should not produce ERROR, but got: " +
                                    errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @Order(1)
    @DisplayName("Positive: persistMetrics without queue_metrics table logs ERROR (non-connection failure)")
    void testPersistWithoutTableLogsError(VertxTestContext testContext) throws InterruptedException {
        // Table does not exist — SQL will fail with relation not found
        Pool pool = createPool();
        metrics = new PeeGeeQMetrics(pool, "test-instance");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        logCapture.clear();
        metrics.persistMetrics(registry)
                .onSuccess(v -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);

                    boolean hasErrorForPersist = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("Failed to persist metrics"));

                    // The failure is a SQL error (table not found), not a connection error,
                    // so it should be logged at ERROR
                    assertTrue(hasErrorForPersist,
                            "Missing table should produce ERROR for persist failure, errors were: " +
                                    errors.stream().map(ILoggingEvent::getFormattedMessage).toList());
                    testContext.completeNow();
                }))
                .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS));
    }

    @Test
    @Order(3)
    @DisplayName("Negative: persistMetrics with connection error during shutdown logs at DEBUG, not ERROR")
    void testPersistConnectionErrorLoggedAsDebug(Vertx vertx, VertxTestContext testContext) throws InterruptedException {
        // Use a separate container we can stop
        @SuppressWarnings("resource")
        PostgreSQLContainer ownContainer = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withReuse(false);
        ownContainer.start();

        PgConnectionManager ownConnMgr = new PgConnectionManager(vertx);
        Pool pool = createPoolFor(ownConnMgr, ownContainer);
        PeeGeeQMetrics ownMetrics = new PeeGeeQMetrics(pool, "test-instance");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ownMetrics.bindTo(registry);
        registry.counter("peegeeq.messages.sent").increment();

        // Stop the container to cause connection error
        ownContainer.stop();
        logCapture.clear();

        ownMetrics.persistMetrics(registry)
                .compose(v -> ownConnMgr.closeAsync().recover(t -> Future.succeededFuture()))
                .onComplete(ar -> testContext.verify(() -> {
                    List<ILoggingEvent> errors = logCapture.eventsAtLevel(Level.ERROR);
                    boolean hasErrorForPersist = errors.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("Failed to persist metrics"));
                    // Connection errors during shutdown should go to DEBUG, not ERROR
                    assertFalse(hasErrorForPersist,
                            "Connection error should be at DEBUG level, not ERROR");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    // --- Helpers ---

    private Pool createPool() {
        return createPoolFor(connectionManager, postgres);
    }

    @SuppressWarnings("unchecked")
    private Pool createPoolFor(PgConnectionManager mgr, PostgreSQLContainer container) {
        PgConnectionConfig config = new PgConnectionConfig.Builder()
                .host(container.getHost())
                .port(container.getFirstMappedPort())
                .database(container.getDatabaseName())
                .username(container.getUsername())
                .password(container.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .maxSize(2)
                .build();

        return mgr.getOrCreateReactivePool("metrics-test", config, poolConfig);
    }

    private void createMetricsTable() {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS queue_metrics (
                    id BIGSERIAL PRIMARY KEY,
                    metric_name VARCHAR(100) NOT NULL,
                    metric_value DOUBLE PRECISION NOT NULL,
                    tags JSONB DEFAULT '{}',
                    timestamp TIMESTAMP WITH TIME ZONE DEFAULT NOW()
                )
                """);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create queue_metrics table", e);
        }
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
