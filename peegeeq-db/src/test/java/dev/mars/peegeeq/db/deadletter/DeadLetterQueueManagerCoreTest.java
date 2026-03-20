package dev.mars.peegeeq.db.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE tests for DeadLetterQueueManager using TestContainers.
 *
 * <p>These tests are tagged as CORE because they:
 * <ul>
 *   <li>Run fast (each test completes in <1 second)</li>
 *   <li>Are isolated (each test focuses on a single method)</li>
 *   <li>Test one component at a time (DeadLetterQueueManager only)</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-11-27
 * @version 1.0
 */
@Tag(TestCategories.CORE)
@ExtendWith(VertxExtension.class)
@Execution(ExecutionMode.SAME_THREAD)  // Run tests sequentially to avoid data conflicts
public class DeadLetterQueueManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private DeadLetterQueueManager deadLetterQueueManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp(VertxTestContext testContext) throws Exception {
        // Create connection manager using the shared Vertx instance
        connectionManager = new PgConnectionManager(manager.getVertx(), null);

        // Get PostgreSQL container and create pool
        PostgreSQLContainer postgres = getPostgres();
        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
            .host(postgres.getHost())
            .port(postgres.getFirstMappedPort())
            .database(postgres.getDatabaseName())
            .username(postgres.getUsername())
            .password(postgres.getPassword())
            .schema("public")
            .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
            .maxSize(10)
            .build();

        reactivePool = connectionManager.getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

        // Create object mapper
        objectMapper = new ObjectMapper();

        // Create dead letter queue manager
        deadLetterQueueManager = new DeadLetterQueueManager(reactivePool, objectMapper);

        // Clean up any existing data from previous tests
        cleanupDeadLetterQueue()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(v -> testContext.completeNow());
    }

    private Future<Void> cleanupDeadLetterQueue() {
        return reactivePool.<Void>withConnection(connection ->
            connection.preparedQuery("DELETE FROM dead_letter_queue").execute().mapEmpty()
        ).recover(e -> Future.succeededFuture());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    void testDeadLetterQueueManagerCreation() {
        assertNotNull(deadLetterQueueManager);
    }

    @Test
    void testMoveToDeadLetterQueue(VertxTestContext testContext) {
        String topic = "test-topic";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        Instant originalCreatedAt = Instant.now();
        String failureReason = "Test failure";
        int retryCount = 3;
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        String correlationId = "corr-123";
        String messageGroup = "group-1";

        moveToDeadLetterQueue("outbox", 1L, topic, payload, originalCreatedAt,
                failureReason, retryCount, headers, correlationId, messageGroup)
            .compose(v -> getDeadLetterMessages(topic, 10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                assertEquals(1, messages.size());
                DeadLetterMessage message = messages.get(0);
                assertEquals("outbox", message.getOriginalTable());
                assertEquals(1L, message.getOriginalId());
                assertEquals(topic, message.getTopic());
                assertEquals(failureReason, message.getFailureReason());
                assertEquals(retryCount, message.getRetryCount());
                assertEquals(correlationId, message.getCorrelationId());
                assertEquals(messageGroup, message.getMessageGroup());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessages(VertxTestContext testContext) {
        String topic = "test-topic";

        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("index", idx);
                return moveToDeadLetterQueue("outbox", (long) idx, topic, payload,
                    Instant.now(), "Test failure " + idx, idx, null, null, null);
            });
        }

        chain.compose(v -> getDeadLetterMessages(topic, 10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                assertEquals(5, messages.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessagesWithPagination(VertxTestContext testContext) {
        String topic = "test-topic-pagination";

        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("index", idx);
                return moveToDeadLetterQueue("outbox", (long) idx, topic, payload,
                    Instant.now(), "Test failure " + idx, idx, null, null, null);
            });
        }

        chain.compose(v -> getDeadLetterMessages(topic, 5, 0))
            .compose(page1 -> {
                return getDeadLetterMessages(topic, 5, 5).map(page2 -> {
                    assertEquals(5, page1.size());
                    assertEquals(5, page2.size());
                    assertNotEquals(page1.get(0).getId(), page2.get(0).getId());
                    return null;
                });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetAllDeadLetterMessages(VertxTestContext testContext) {

        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("index", idx);
                return moveToDeadLetterQueue("outbox", (long) idx, "topic-" + idx, payload,
                    Instant.now(), "Test failure " + idx, idx, null, null, null);
            });
        }

        chain.compose(v -> getAllDeadLetterMessages(10, 0))
            .onSuccess(allMessages -> testContext.verify(() -> {
                assertTrue(allMessages.size() >= 3);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessage(VertxTestContext testContext) {
        String topic = "test-topic-get";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue("outbox", 1L, topic, payload, Instant.now(),
                "Test failure", 3, null, null, null)
            .compose(v -> getDeadLetterMessages(topic, 1, 0))
            .compose(messages -> {
                assertEquals(1, messages.size());
                long messageId = messages.get(0).getId();
                return getDeadLetterMessage(messageId).map(retrieved -> {
                    assertTrue(retrieved.isPresent());
                    assertEquals(messageId, retrieved.get().getId());
                    assertEquals(topic, retrieved.get().getTopic());
                    return null;
                });
            })
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessageNotFound(VertxTestContext testContext) {
        getDeadLetterMessage(999999L)
            .onSuccess(retrieved -> testContext.verify(() -> {
                assertFalse(retrieved.isPresent());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeleteDeadLetterMessage(VertxTestContext testContext) {
        String topic = "test-topic-delete";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue("outbox", 1L, topic, payload, Instant.now(),
                "Test failure", 3, null, null, null)
            .compose(v -> getDeadLetterMessages(topic, 1, 0))
            .compose(messages -> {
                assertEquals(1, messages.size());
                long messageId = messages.get(0).getId();
                return deleteDeadLetterMessage(messageId, "Test deletion")
                    .compose(deleted -> {
                        assertTrue(deleted);
                        return getDeadLetterMessage(messageId);
                    });
            })
            .onSuccess(retrieved -> testContext.verify(() -> {
                assertFalse(retrieved.isPresent());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeleteNonExistentMessage(VertxTestContext testContext) {
        deleteDeadLetterMessage(999999L, "Test deletion")
            .onSuccess(deleted -> testContext.verify(() -> {
                assertFalse(deleted);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testConcurrentReprocessSameMessageOnlyReinsertsOnce(VertxTestContext testContext) {
        String topic = "test-topic-concurrent-reprocess";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue("outbox", 1L, topic, payload, Instant.now(),
                "Test failure", 3, null, null, null)
            .compose(v -> getDeadLetterMessages(topic, 1, 0))
            .compose(messages -> {
                assertEquals(1, messages.size());
                long messageId = messages.get(0).getId();

                Future<Boolean> f1 = deadLetterQueueManager.reprocessDeadLetterMessageRecord(messageId, "reprocess-attempt-1");
                Future<Boolean> f2 = deadLetterQueueManager.reprocessDeadLetterMessageRecord(messageId, "reprocess-attempt-2");

                return Future.all(f1, f2).compose(cf -> {
                    boolean r1 = f1.result();
                    boolean r2 = f2.result();
                    int successCount = (r1 ? 1 : 0) + (r2 ? 1 : 0);
                    assertEquals(1, successCount, "Exactly one concurrent reprocess should succeed");

                    return getDeadLetterMessage(messageId).compose(opt -> {
                        assertTrue(opt.isEmpty(),
                            "Message should be removed from dead_letter_queue after successful reprocess");
                        return countOutboxRowsByTopic(topic);
                    });
                });
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertEquals(1, count, "Exactly one message should be reinserted into outbox");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCleanupOldMessagesRejectsNonPositiveRetentionDays(VertxTestContext testContext) {
        assertFutureFailure(cleanupOldMessages(0))
            .compose(v -> assertFutureFailure(cleanupOldMessages(-1)))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testSyncReadApisFailFastWhenDeadLetterTableUnavailable(VertxTestContext testContext) {
        renameDeadLetterTable("dead_letter_queue_tmp")
            .compose(v -> assertFutureFailure(getDeadLetterMessages("topic", 10, 0)))
            .compose(v -> assertFutureFailure(getAllDeadLetterMessages(10, 0)))
            .compose(v -> assertFutureFailure(getDeadLetterMessage(1L)))
            .compose(v -> assertFutureFailure(getStatistics()))
            .eventually(() -> renameDeadLetterTableBack("dead_letter_queue_tmp"))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testAsyncReadApisCompleteExceptionallyWhenDeadLetterTableUnavailable(VertxTestContext testContext) {
        renameDeadLetterTable("dead_letter_queue_tmp")
            .compose(v -> assertFutureFailure(deadLetterQueueManager.getDeadLetterMessages("topic", 10, 0)))
            .compose(v -> assertFutureFailure(deadLetterQueueManager.getAllDeadLetterMessages(10, 0)))
            .compose(v -> assertFutureFailure(deadLetterQueueManager.getDeadLetterMessage(1L)))
            .compose(v -> assertFutureFailure(deadLetterQueueManager.getStatistics()))
            .eventually(() -> renameDeadLetterTableBack("dead_letter_queue_tmp"))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testAsyncWriteApisCompleteExceptionallyWhenDeadLetterTableUnavailable(VertxTestContext testContext) {
        renameDeadLetterTable("dead_letter_queue_tmp")
            .compose(v -> assertFutureFailure(deadLetterQueueManager.reprocessDeadLetterMessage(1L, "reason")))
            .compose(v -> assertFutureFailure(deadLetterQueueManager.deleteDeadLetterMessage(1L, "reason")))
            .compose(v -> assertFutureFailure(deadLetterQueueManager.cleanupOldMessages(1)))
            .eventually(() -> renameDeadLetterTableBack("dead_letter_queue_tmp"))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testSyncWriteInternalApisFailFastWhenDeadLetterTableUnavailable(VertxTestContext testContext) {
        renameDeadLetterTable("dead_letter_queue_tmp")
            .compose(v -> assertFutureFailure(reprocessDeadLetterMessage(1L, "reason")))
            .compose(v -> assertFutureFailure(deleteDeadLetterMessage(1L, "reason")))
            .compose(v -> assertFutureFailure(cleanupOldMessages(1)))
            .eventually(() -> renameDeadLetterTableBack("dead_letter_queue_tmp"))
            .onSuccess(v -> testContext.completeNow())
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetStatistics(VertxTestContext testContext) {

        Future<Void> chain = Future.succeededFuture();
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            chain = chain.compose(v -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("index", idx);
                return moveToDeadLetterQueue("outbox", (long) idx, "topic-" + (idx % 2),
                    payload, Instant.now(), "Test failure " + idx, idx, null, null, null);
            });
        }

        chain.compose(v -> getStatistics())
            .onSuccess(stats -> testContext.verify(() -> {
                assertNotNull(stats);
                assertTrue(stats.getTotalMessages() >= 5);
                assertTrue(stats.getUniqueTopics() >= 2);
                assertTrue(stats.getUniqueTables() >= 1);
                assertNotNull(stats.getOldestFailure());
                assertNotNull(stats.getNewestFailure());
                assertTrue(stats.getAverageRetryCount() >= 0);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testMoveToDeadLetterQueueWithNullHeaders(VertxTestContext testContext) {
        String topic = "test-topic-null-headers";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue("outbox", 1L, topic, payload, Instant.now(),
                "Test failure", 3, null, null, null)
            .compose(v -> getDeadLetterMessages(topic, 1, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                assertEquals(1, messages.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessagesWithLargeLimit(VertxTestContext testContext) {
        String topic = "test-topic-large-limit";
        getDeadLetterMessages(topic, 1000, 0)
            .onSuccess(messages -> testContext.verify(() -> {
                assertNotNull(messages);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessagesWithLargeOffset(VertxTestContext testContext) {
        String topic = "test-topic-large-offset";
        getDeadLetterMessages(topic, 10, 1000)
            .onSuccess(messages -> testContext.verify(() -> {
                assertNotNull(messages);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessagesRejectsInvalidPagination() {
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.fetchDeadLetterMessagesByTopic("topic", 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.fetchDeadLetterMessagesByTopic("topic", 10, -1));
    }

    @Test
    void testGetAllDeadLetterMessagesRejectsInvalidPagination() {
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.fetchAllDeadLetterMessages(0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.fetchAllDeadLetterMessages(10, -1));
    }

    @Test
    void testApiPaginationRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.getDeadLetterMessages("topic", 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.getDeadLetterMessages("topic", 10, -1));
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.getAllDeadLetterMessages(0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> deadLetterQueueManager.getAllDeadLetterMessages(10, -1));
    }

    @Test
    void testMoveToDeadLetterQueueRejectsInvalidArguments() {
        assertThrows(NullPointerException.class, () -> deadLetterQueueManager.moveToDeadLetterQueue(
            "outbox", 1L, "topic", Map.of("k", "v"), null,
            "failure", 1, null, null, null));

        assertThrows(IllegalArgumentException.class, () -> deadLetterQueueManager.moveToDeadLetterQueue(
            "outbox", 1L, "topic", Map.of("k", "v"), Instant.now(),
            "failure", -1, null, null, null));
    }

    @Test
    void testFailedAtAlwaysPresentForStoredMessages(VertxTestContext testContext) {
        String topic = "test-topic-failed-at-present";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue("outbox", 1L, topic, payload, Instant.now(),
                "Test failure", 1, null, null, null)
            .compose(v -> getDeadLetterMessages(topic, 1, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                assertEquals(1, messages.size());
                assertNotNull(messages.get(0).getFailedAt());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessagesMultipleCalls(VertxTestContext testContext) {
        String topic = "test-topic-get-multiple";
        getDeadLetterMessages(topic, 10, 0)
            .compose(messages1 -> {
                assertNotNull(messages1);
                return getDeadLetterMessages(topic, 10, 0);
            })
            .onSuccess(messages2 -> testContext.verify(() -> {
                assertNotNull(messages2);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private <T> Future<Void> assertFutureFailure(Future<T> future) {
        return future
            .map(result -> {
                fail("Expected future to fail but it succeeded with: " + result);
                return (Void) null;
            })
            .recover(err -> Future.succeededFuture());
    }

    private Future<Integer> countOutboxRowsByTopic(String topic) {
        return reactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT COUNT(*) AS cnt FROM outbox WHERE topic = $1")
                .execute(io.vertx.sqlclient.Tuple.of(topic))
                .map(rows -> rows.iterator().next().getInteger("cnt"))
        );
    }

    private Future<Void> renameDeadLetterTable(String temporaryName) {
        return reactivePool.withConnection(connection ->
            connection.query("ALTER TABLE dead_letter_queue RENAME TO " + temporaryName).execute().mapEmpty()
        );
    }

    private Future<Void> renameDeadLetterTableBack(String temporaryName) {
        return reactivePool.withConnection(connection ->
            connection.query("ALTER TABLE " + temporaryName + " RENAME TO dead_letter_queue").execute().mapEmpty()
        );
    }

    private Future<Void> moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                       Object payload, Instant originalCreatedAt, String failureReason,
                                       int retryCount, Map<String, String> headers, String correlationId,
                                       String messageGroup) {
        return deadLetterQueueManager
            .moveToDeadLetterQueue(originalTable, originalId, topic, payload, originalCreatedAt,
                failureReason, retryCount, headers, correlationId, messageGroup);
    }

    private Future<List<DeadLetterMessage>> getDeadLetterMessages(String topic, int limit, int offset) {
        return deadLetterQueueManager.fetchDeadLetterMessagesByTopic(topic, limit, offset);
    }

    private Future<List<DeadLetterMessage>> getAllDeadLetterMessages(int limit, int offset) {
        return deadLetterQueueManager.fetchAllDeadLetterMessages(limit, offset);
    }

    private Future<Optional<DeadLetterMessage>> getDeadLetterMessage(long id) {
        return deadLetterQueueManager.fetchDeadLetterMessage(id);
    }

    private Future<Boolean> reprocessDeadLetterMessage(long id, String reason) {
        return deadLetterQueueManager.reprocessDeadLetterMessageRecord(id, reason);
    }

    private Future<Boolean> deleteDeadLetterMessage(long id, String reason) {
        return deadLetterQueueManager.removeDeadLetterMessage(id, reason);
    }

    private Future<DeadLetterQueueStats> getStatistics() {
        return deadLetterQueueManager.fetchStatistics();
    }

    private Future<Integer> cleanupOldMessages(int retentionDays) {
        return deadLetterQueueManager.purgeOldDeadLetterMessages(retentionDays);
    }
}
