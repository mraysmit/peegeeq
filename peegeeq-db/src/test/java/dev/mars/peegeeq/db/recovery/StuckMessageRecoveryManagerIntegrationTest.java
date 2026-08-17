package dev.mars.peegeeq.db.recovery;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 */

import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link StuckMessageRecoveryManager} using Testcontainers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@Isolated("Stuck-message recovery updates every stale PROCESSING outbox row")
public class StuckMessageRecoveryManagerIntegrationTest extends BaseIntegrationTest {

    private static final String FAULTED_ERROR_MESSAGE_COLUMN = "error_message_recovery_fault";

    private PgConnectionManager connectionManager;
    private Pool pool;
    private StuckMessageRecoveryManager recoveryManager;
    private String testTopic;
    private boolean errorMessageColumnRenamed;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        // The test drives its dedicated recovery manager explicitly. Prevent the manager-owned
        // periodic recovery task from racing the exact fixture assertions below.
        manager.getStuckMessageRecoveryManager().markClosing();
        connectionManager = new PgConnectionManager(manager.getVertx());
        testTopic = "stuck-recovery-manager-" + UUID.randomUUID();

        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema(PostgreSQLTestConstants.TEST_SCHEMA)
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(3)
            .shared(false)
            .idleTimeout(Duration.ofSeconds(2))
            .connectionTimeout(Duration.ofSeconds(5))
            .build();
        pool = connectionManager.getOrCreateReactivePool("test-recovery", connectionConfig, poolConfig);

        pool.withTransaction(client -> client
                .preparedQuery("DELETE FROM outbox WHERE status = 'PROCESSING'")
                .execute())
            .map(rows -> {
                recoveryManager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), true);
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> cleanup = restoreErrorMessageColumn()
            .compose(v -> pool != null && testTopic != null
                ? pool.withTransaction(client -> client
                        .preparedQuery("DELETE FROM outbox WHERE topic = $1")
                        .execute(Tuple.of(testTopic)))
                    .mapEmpty()
                : Future.succeededFuture());

        cleanup.transform(cleanupResult -> {
                Future<Void> close = connectionManager != null
                    ? connectionManager.close()
                    : Future.succeededFuture();
                return close.transform(closeResult -> {
                    if (cleanupResult.failed()) {
                        return Future.failedFuture(cleanupResult.cause());
                    }
                    if (closeResult.failed()) {
                        return Future.failedFuture(closeResult.cause());
                    }
                    return Future.succeededFuture();
                });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testStuckMessageRecoveryManagerCreation() {
        assertNotNull(recoveryManager);
    }

    @Test
    void testStuckMessageRecoveryManagerCreationEnabled() {
        StuckMessageRecoveryManager manager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), true);
        assertNotNull(manager);
    }

    @Test
    void testStuckMessageRecoveryManagerCreationDisabled() {
        StuckMessageRecoveryManager manager = new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        assertNotNull(manager);
    }

    @Test
    void testRecoverStuckMessagesWhenDisabled(VertxTestContext testContext) {
        StuckMessageRecoveryManager disabledManager =
            new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);

        insertProcessingMessage(Duration.ofMinutes(10))
            .compose(messageId -> disabledManager.recoverStuckMessages()
                .map(recovered -> {
                    assertEquals(0, recovered, "Disabled recovery must not update a stale row");
                    return messageId;
                }))
            .compose(this::fetchMessageState)
            .map(state -> {
                assertEquals("PROCESSING", state.status());
                assertNotNull(state.processedAt());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testRecoverStuckMessagesNoStuckMessages(VertxTestContext testContext) {
        insertProcessingMessage(Duration.ofMinutes(1))
            .compose(messageId -> recoveryManager.recoverStuckMessages()
                .map(recovered -> {
                    assertEquals(0, recovered, "A recent PROCESSING row must not be recovered");
                    return messageId;
                }))
            .compose(this::fetchMessageState)
            .map(state -> {
                assertEquals("PROCESSING", state.status());
                assertNotNull(state.processedAt());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetRecoveryStats(VertxTestContext testContext) {
        insertProcessingMessage(Duration.ofMinutes(10))
            .compose(ignored -> insertProcessingMessage(Duration.ofMinutes(1)))
            .compose(ignored -> recoveryManager.getRecoveryStats())
            .map(stats -> {
                assertNotNull(stats);
                assertTrue(stats.isEnabled());
                assertEquals(1, stats.getStuckMessagesCount());
                assertEquals(2, stats.getTotalProcessingCount());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetRecoveryStatsWhenDisabled(VertxTestContext testContext) {
        StuckMessageRecoveryManager disabledManager =
            new StuckMessageRecoveryManager(pool, Duration.ofMinutes(5), false);
        disabledManager.getRecoveryStats()
            .map(stats -> {
                assertNotNull(stats);
                assertFalse(stats.isEnabled());
                assertEquals(0, stats.getStuckMessagesCount());
                assertEquals(0, stats.getTotalProcessingCount());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testRecoveryStatsToString() {
        StuckMessageRecoveryManager.RecoveryStats stats =
            new StuckMessageRecoveryManager.RecoveryStats(5, 10, true);
        String toString = stats.toString();
        assertTrue(toString.contains("stuck=5"));
        assertTrue(toString.contains("totalProcessing=10"));
        assertTrue(toString.contains("enabled=true"));
    }

    @Test
    void testRecoveryStatsGetters() {
        StuckMessageRecoveryManager.RecoveryStats stats =
            new StuckMessageRecoveryManager.RecoveryStats(5, 10, true);
        assertEquals(5, stats.getStuckMessagesCount());
        assertEquals(10, stats.getTotalProcessingCount());
        assertTrue(stats.isEnabled());
    }

    @Test
    void testRecoverStuckMessagesMultipleCalls(VertxTestContext testContext) {
        insertProcessingMessage(Duration.ofMinutes(10))
            .compose(messageId -> recoveryManager.recoverStuckMessages()
                .map(firstCount -> {
                    assertEquals(1, firstCount, "The first call should recover the stale row");
                    return messageId;
                }))
            .compose(messageId -> recoveryManager.recoverStuckMessages()
                .map(secondCount -> {
                    assertEquals(0, secondCount, "The second call must be idempotent");
                    return messageId;
                }))
            .compose(this::fetchMessageState)
            .map(state -> {
                assertEquals("PENDING", state.status());
                assertNull(state.processedAt());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testRecoveryPropagatesDiagnosticLoggingFailure(VertxTestContext testContext) {
        insertProcessingMessage(Duration.ofMinutes(10))
            .compose(messageId -> renameErrorMessageColumnForFault()
                .map(v -> messageId))
            .compose(messageId -> recoveryManager.recoverStuckMessages()
                .transform(recoveryResult -> restoreErrorMessageColumn()
                    .compose(v -> {
                        if (recoveryResult.succeeded()) {
                            return Future.failedFuture(new AssertionError(
                                "Recovery must not succeed when recovered-row logging fails"));
                        }
                        assertInstanceOf(PgException.class, recoveryResult.cause());
                        return fetchMessageState(messageId)
                            .map(state -> {
                                assertEquals("PROCESSING", state.status(),
                                    "A failed transactional recovery must roll back the state change");
                                assertNotNull(state.processedAt());
                                return (Void) null;
                            });
                    })))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetRecoveryStatsMultipleCalls(VertxTestContext testContext) {
        insertProcessingMessage(Duration.ofMinutes(10))
            .compose(ignored -> insertProcessingMessage(Duration.ofMinutes(1)))
            .compose(ignored -> recoveryManager.getRecoveryStats())
            .compose(firstStats -> {
                assertEquals(1, firstStats.getStuckMessagesCount());
                assertEquals(2, firstStats.getTotalProcessingCount());
                return recoveryManager.getRecoveryStats();
            })
            .map(secondStats -> {
                assertEquals(1, secondStats.getStuckMessagesCount());
                assertEquals(2, secondStats.getTotalProcessingCount());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testRecoveryManagerWithDifferentTimeouts(VertxTestContext testContext) {
        StuckMessageRecoveryManager oneMinuteManager =
            new StuckMessageRecoveryManager(pool, Duration.ofMinutes(1), true);
        StuckMessageRecoveryManager tenMinuteManager =
            new StuckMessageRecoveryManager(pool, Duration.ofMinutes(10), true);

        insertProcessingMessage(Duration.ofMinutes(2))
            .compose(twoMinuteId -> insertProcessingMessage(Duration.ofMinutes(20))
                .map(twentyMinuteId -> new MessageIds(twoMinuteId, twentyMinuteId)))
            .compose(ids -> tenMinuteManager.recoverStuckMessages()
                .map(recovered -> {
                    assertEquals(1, recovered, "The ten-minute manager should recover only the oldest row");
                    return ids;
                }))
            .compose(ids -> Future.all(
                    fetchMessageState(ids.twoMinuteId()),
                    fetchMessageState(ids.twentyMinuteId()))
                .map(states -> {
                    MessageState twoMinuteState = states.resultAt(0);
                    MessageState twentyMinuteState = states.resultAt(1);
                    assertEquals("PROCESSING", twoMinuteState.status());
                    assertNotNull(twoMinuteState.processedAt());
                    assertEquals("PENDING", twentyMinuteState.status());
                    assertNull(twentyMinuteState.processedAt());
                    return ids.twoMinuteId();
                }))
            .compose(twoMinuteId -> oneMinuteManager.recoverStuckMessages()
                .map(recovered -> {
                    assertEquals(1, recovered, "The one-minute manager should recover the remaining row");
                    return twoMinuteId;
                }))
            .compose(this::fetchMessageState)
            .map(state -> {
                assertEquals("PENDING", state.status());
                assertNull(state.processedAt());
                return (Void) null;
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    private Future<Long> insertProcessingMessage(Duration age) {
        String sql = """
            INSERT INTO outbox
                (topic, payload, status, processed_at, retry_count, created_at, priority)
            VALUES ($1, $2::jsonb, 'PROCESSING', $3, 0, NOW(), 5)
            RETURNING id
            """;
        OffsetDateTime processedAt = OffsetDateTime.now(ZoneOffset.UTC).minus(age);

        return pool.withTransaction(client -> client.preparedQuery(sql)
            .execute(Tuple.of(testTopic, "\"recovery fixture\"", processedAt))
            .map(rows -> {
                assertEquals(1, rows.size(), "The fixture insert must return one row");
                return rows.iterator().next().getLong("id");
            }));
    }

    private Future<MessageState> fetchMessageState(long messageId) {
        String sql = "SELECT status, processed_at FROM outbox WHERE id = $1 AND topic = $2";
        return pool.withConnection(client -> client.preparedQuery(sql)
            .execute(Tuple.of(messageId, testTopic))
            .map(rows -> {
                assertEquals(1, rows.size(), "The fixture message must still exist");
                var row = rows.iterator().next();
                return new MessageState(
                    row.getString("status"),
                    row.getOffsetDateTime("processed_at"));
            }));
    }

    private Future<Void> renameErrorMessageColumnForFault() {
        String sql = "ALTER TABLE outbox RENAME COLUMN error_message TO " + FAULTED_ERROR_MESSAGE_COLUMN;
        return pool.withTransaction(client -> client.query(sql).execute())
            .map(rows -> {
                errorMessageColumnRenamed = true;
                return (Void) null;
            });
    }

    private Future<Void> restoreErrorMessageColumn() {
        if (!errorMessageColumnRenamed || pool == null) {
            return Future.succeededFuture();
        }
        String sql = "ALTER TABLE outbox RENAME COLUMN " + FAULTED_ERROR_MESSAGE_COLUMN + " TO error_message";
        return pool.withTransaction(client -> client.query(sql).execute())
            .map(rows -> {
                errorMessageColumnRenamed = false;
                return (Void) null;
            });
    }

    private record MessageIds(long twoMinuteId, long twentyMinuteId) {
    }

    private record MessageState(String status, OffsetDateTime processedAt) {
    }
}
