package dev.mars.peegeeq.db.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
@Execution(ExecutionMode.SAME_THREAD)  // Run tests sequentially to avoid data conflicts
public class DeadLetterQueueManagerCoreTest extends BaseIntegrationTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private DeadLetterQueueManager deadLetterQueueManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
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
        cleanupDeadLetterQueue();
    }

    private void cleanupDeadLetterQueue() {
        try {
            awaitFuture(reactivePool.withConnection(connection ->
                connection.preparedQuery("DELETE FROM dead_letter_queue").execute()
                    .map(rowSet -> (Void) null)
            ));
        } catch (Exception e) {
            // Ignore cleanup errors
        }
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
    void testMoveToDeadLetterQueue() {
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

        // Move message to dead letter queue
        assertDoesNotThrow(() -> moveToDeadLetterQueue(
            "outbox",
            1L,
            topic,
            payload,
            originalCreatedAt,
            failureReason,
            retryCount,
            headers,
            correlationId,
            messageGroup
        ));

        // Verify message was added
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 10, 0);
        assertEquals(1, messages.size());
        DeadLetterMessage message = messages.get(0);
        assertEquals("outbox", message.getOriginalTable());
        assertEquals(1L, message.getOriginalId());
        assertEquals(topic, message.getTopic());
        assertEquals(failureReason, message.getFailureReason());
        assertEquals(retryCount, message.getRetryCount());
        assertEquals(correlationId, message.getCorrelationId());
        assertEquals(messageGroup, message.getMessageGroup());
    }

    @Test
    void testGetDeadLetterMessages() {
        // Add some test messages
        String topic = "test-topic";
        for (int i = 0; i < 5; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            moveToDeadLetterQueue(
                "outbox",
                (long) i,
                topic,
                payload,
                Instant.now(),
                "Test failure " + i,
                i,
                null,
                null,
                null
            );
        }

        // Retrieve messages
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 10, 0);
        assertEquals(5, messages.size());
    }

    @Test
    void testGetDeadLetterMessagesWithPagination() {
        // Add some test messages
        String topic = "test-topic-pagination";
        for (int i = 0; i < 10; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            moveToDeadLetterQueue(
                "outbox",
                (long) i,
                topic,
                payload,
                Instant.now(),
                "Test failure " + i,
                i,
                null,
                null,
                null
            );
        }

        // Retrieve first page
        List<DeadLetterMessage> page1 = getDeadLetterMessages(topic, 5, 0);
        assertEquals(5, page1.size());

        // Retrieve second page
        List<DeadLetterMessage> page2 = getDeadLetterMessages(topic, 5, 5);
        assertEquals(5, page2.size());

        // Verify pages are different
        assertNotEquals(page1.get(0).getId(), page2.get(0).getId());
    }

    @Test
    void testGetAllDeadLetterMessages() {
        // Add messages to different topics
        for (int i = 0; i < 3; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            moveToDeadLetterQueue(
                "outbox",
                (long) i,
                "topic-" + i,
                payload,
                Instant.now(),
                "Test failure " + i,
                i,
                null,
                null,
                null
            );
        }

        // Retrieve all messages
        List<DeadLetterMessage> allMessages = getAllDeadLetterMessages(10, 0);
        assertTrue(allMessages.size() >= 3);
    }

    @Test
    void testGetDeadLetterMessage() {
        // Add a test message
        String topic = "test-topic-get";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        moveToDeadLetterQueue(
            "outbox",
            1L,
            topic,
            payload,
            Instant.now(),
            "Test failure",
            3,
            null,
            null,
            null
        );

        // Get the message
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
        long messageId = messages.get(0).getId();

        // Retrieve by ID
        Optional<DeadLetterMessage> retrieved = getDeadLetterMessage(messageId);
        assertTrue(retrieved.isPresent());
        assertEquals(messageId, retrieved.get().getId());
        assertEquals(topic, retrieved.get().getTopic());
    }

    @Test
    void testGetDeadLetterMessageNotFound() {
        // Try to get a non-existent message
        Optional<DeadLetterMessage> retrieved = getDeadLetterMessage(999999L);
        assertFalse(retrieved.isPresent());
    }

    @Test
    void testDeleteDeadLetterMessage() {
        // Add a test message
        String topic = "test-topic-delete";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        moveToDeadLetterQueue(
            "outbox",
            1L,
            topic,
            payload,
            Instant.now(),
            "Test failure",
            3,
            null,
            null,
            null
        );

        // Get the message ID
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
        long messageId = messages.get(0).getId();

        // Delete the message
        boolean deleted = deleteDeadLetterMessage(messageId, "Test deletion");
        assertTrue(deleted);

        // Verify message is gone
        Optional<DeadLetterMessage> retrieved = getDeadLetterMessage(messageId);
        assertFalse(retrieved.isPresent());
    }

    @Test
    void testDeleteNonExistentMessage() {
        // Try to delete a non-existent message
        boolean deleted = deleteDeadLetterMessage(999999L, "Test deletion");
        assertFalse(deleted);
    }

    @Test
    void testConcurrentReprocessSameMessageOnlyReinsertsOnce() {
        String topic = "test-topic-concurrent-reprocess";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue(
            "outbox",
            1L,
            topic,
            payload,
            Instant.now(),
            "Test failure",
            3,
            null,
            null,
            null
        );

        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
        long messageId = messages.get(0).getId();

        Future<Boolean> f1 = deadLetterQueueManager.reprocessDeadLetterMessageRecord(messageId, "reprocess-attempt-1");
        Future<Boolean> f2 = deadLetterQueueManager.reprocessDeadLetterMessageRecord(messageId, "reprocess-attempt-2");

        awaitFuture(Future.join(f1, f2));

        boolean r1 = f1.result();
        boolean r2 = f2.result();
        int successCount = (r1 ? 1 : 0) + (r2 ? 1 : 0);

        assertEquals(1, successCount, "Exactly one concurrent reprocess should succeed");
        assertTrue(getDeadLetterMessage(messageId).isEmpty(),
            "Message should be removed from dead_letter_queue after successful reprocess");
        assertEquals(1, countOutboxRowsByTopic(topic),
            "Exactly one message should be reinserted into outbox");
    }

    @Test
    void testCleanupOldMessagesRejectsNonPositiveRetentionDays() {
        assertThrows(RuntimeException.class, () -> cleanupOldMessages(0));
        assertThrows(RuntimeException.class, () -> cleanupOldMessages(-1));
    }

    @Test
    void testSyncReadApisFailFastWhenDeadLetterTableUnavailable() {
        renameDeadLetterTable("dead_letter_queue_tmp");
        try {
            assertThrows(RuntimeException.class,
                () -> getDeadLetterMessages("topic", 10, 0));
            assertThrows(RuntimeException.class,
                () -> getAllDeadLetterMessages(10, 0));
            assertThrows(RuntimeException.class,
                () -> getDeadLetterMessage(1L));
            assertThrows(RuntimeException.class,
                () -> getStatistics());
        } finally {
            renameDeadLetterTableBack("dead_letter_queue_tmp");
        }
    }

    @Test
    void testAsyncReadApisCompleteExceptionallyWhenDeadLetterTableUnavailable() {
        renameDeadLetterTable("dead_letter_queue_tmp");
        try {
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.getDeadLetterMessages("topic", 10, 0)));
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.getAllDeadLetterMessages(10, 0)));
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.getDeadLetterMessage(1L)));
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.getStatistics()));
        } finally {
            renameDeadLetterTableBack("dead_letter_queue_tmp");
        }
    }

    @Test
    void testAsyncWriteApisCompleteExceptionallyWhenDeadLetterTableUnavailable() {
        renameDeadLetterTable("dead_letter_queue_tmp");
        try {
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.reprocessDeadLetterMessage(1L, "reason")));
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.deleteDeadLetterMessage(1L, "reason")));
            assertThrows(RuntimeException.class,
                () -> awaitFuture(deadLetterQueueManager.cleanupOldMessages(1)));
        } finally {
            renameDeadLetterTableBack("dead_letter_queue_tmp");
        }
    }

    @Test
    void testSyncWriteInternalApisFailFastWhenDeadLetterTableUnavailable() {
        renameDeadLetterTable("dead_letter_queue_tmp");
        try {
            assertThrows(RuntimeException.class,
                () -> reprocessDeadLetterMessage(1L, "reason"));
            assertThrows(RuntimeException.class,
                () -> deleteDeadLetterMessage(1L, "reason"));
            assertThrows(RuntimeException.class,
                () -> cleanupOldMessages(1));
        } finally {
            renameDeadLetterTableBack("dead_letter_queue_tmp");
        }
    }

    @Test
    void testGetStatistics() {
        // Add some test messages
        for (int i = 0; i < 5; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            moveToDeadLetterQueue(
                "outbox",
                (long) i,
                "topic-" + (i % 2),  // 2 unique topics
                payload,
                Instant.now(),
                "Test failure " + i,
                i,
                null,
                null,
                null
            );
        }

        // Get statistics
        DeadLetterQueueStats stats = getStatistics();
        assertNotNull(stats);
        assertTrue(stats.getTotalMessages() >= 5);
        assertTrue(stats.getUniqueTopics() >= 2);
        assertTrue(stats.getUniqueTables() >= 1);
        assertNotNull(stats.getOldestFailure());
        assertNotNull(stats.getNewestFailure());
        assertTrue(stats.getAverageRetryCount() >= 0);
    }

    @Test
    void testMoveToDeadLetterQueueWithNullHeaders() {
        String topic = "test-topic-null-headers";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        // Move message with null headers
        assertDoesNotThrow(() -> moveToDeadLetterQueue(
            "outbox",
            1L,
            topic,
            payload,
            Instant.now(),
            "Test failure",
            3,
            null,  // null headers
            null,
            null
        ));

        // Verify message was added
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
    }

    @Test
    void testGetDeadLetterMessagesWithLargeLimit() {
        String topic = "test-topic-large-limit";
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 1000, 0);
        assertNotNull(messages);
    }

    @Test
    void testGetDeadLetterMessagesWithLargeOffset() {
        String topic = "test-topic-large-offset";
        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 10, 1000);
        assertNotNull(messages);
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
    void testFailedAtAlwaysPresentForStoredMessages() {
        String topic = "test-topic-failed-at-present";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");

        moveToDeadLetterQueue(
            "outbox",
            1L,
            topic,
            payload,
            Instant.now(),
            "Test failure",
            1,
            null,
            null,
            null
        );

        List<DeadLetterMessage> messages = getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
        assertNotNull(messages.get(0).getFailedAt());
    }

    @Test
    void testGetDeadLetterMessagesMultipleCalls() {
        String topic = "test-topic-get-multiple";
        List<DeadLetterMessage> messages1 = getDeadLetterMessages(topic, 10, 0);
        assertNotNull(messages1);

        List<DeadLetterMessage> messages2 = getDeadLetterMessages(topic, 10, 0);
        assertNotNull(messages2);
    }

    private int countOutboxRowsByTopic(String topic) {
        return awaitFuture(reactivePool.withConnection(connection ->
            connection.preparedQuery("SELECT COUNT(*) AS cnt FROM outbox WHERE topic = $1")
                .execute(io.vertx.sqlclient.Tuple.of(topic))
                .map(rows -> rows.iterator().next().getInteger("cnt"))
        ));
    }

    private void renameDeadLetterTable(String temporaryName) {
        awaitFuture(reactivePool.withConnection(connection ->
            connection.query("ALTER TABLE dead_letter_queue RENAME TO " + temporaryName).execute().mapEmpty()
        ));
    }

    private void renameDeadLetterTableBack(String temporaryName) {
        awaitFuture(reactivePool.withConnection(connection ->
            connection.query("ALTER TABLE " + temporaryName + " RENAME TO dead_letter_queue").execute().mapEmpty()
        ));
    }

    private void moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                       Object payload, Instant originalCreatedAt, String failureReason,
                                       int retryCount, Map<String, String> headers, String correlationId,
                                       String messageGroup) {
        awaitFuture(deadLetterQueueManager
            .moveToDeadLetterQueue(originalTable, originalId, topic, payload, originalCreatedAt,
                failureReason, retryCount, headers, correlationId, messageGroup));
    }

    private List<DeadLetterMessage> getDeadLetterMessages(String topic, int limit, int offset) {
        return awaitFuture(deadLetterQueueManager.fetchDeadLetterMessagesByTopic(topic, limit, offset));
    }

    private List<DeadLetterMessage> getAllDeadLetterMessages(int limit, int offset) {
        return awaitFuture(deadLetterQueueManager.fetchAllDeadLetterMessages(limit, offset));
    }

    private Optional<DeadLetterMessage> getDeadLetterMessage(long id) {
        return awaitFuture(deadLetterQueueManager.fetchDeadLetterMessage(id));
    }

    private boolean reprocessDeadLetterMessage(long id, String reason) {
        return awaitFuture(deadLetterQueueManager.reprocessDeadLetterMessageRecord(id, reason));
    }

    private boolean deleteDeadLetterMessage(long id, String reason) {
        return awaitFuture(deadLetterQueueManager.removeDeadLetterMessage(id, reason));
    }

    private DeadLetterQueueStats getStatistics() {
        return awaitFuture(deadLetterQueueManager.fetchStatistics());
    }

    private int cleanupOldMessages(int retentionDays) {
        return awaitFuture(deadLetterQueueManager.purgeOldDeadLetterMessages(retentionDays));
    }

    private <T> T awaitFuture(Future<T> future) {
        VertxTestContext testContext = new VertxTestContext();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        future
            .onSuccess(result::set)
            .onFailure(failure::set)
            .eventually(() -> {
                testContext.completeNow();
                return Future.succeededFuture();
            });

        try {
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Timed out waiting for Future completion");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Future completion", e);
        }

        if (failure.get() != null) {
            throw new RuntimeException(failure.get());
        }
        return result.get();
    }
}
