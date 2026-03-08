package dev.mars.peegeeq.db.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.BaseIntegrationTest;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
        PostgreSQLContainer<?> postgres = getPostgres();
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
            reactivePool.withConnection(connection ->
                connection.preparedQuery("DELETE FROM dead_letter_queue").execute()
                    .map(rowSet -> (Void) null)
            ).toCompletionStage().toCompletableFuture().get();
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

        CompletableFuture<Boolean> f1 = CompletableFuture.supplyAsync(
            () -> reprocessDeadLetterMessage(messageId, "reprocess-attempt-1"));
        CompletableFuture<Boolean> f2 = CompletableFuture.supplyAsync(
            () -> reprocessDeadLetterMessage(messageId, "reprocess-attempt-2"));

        boolean r1 = f1.join();
        boolean r2 = f2.join();
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
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.getDeadLetterMessages("topic", 10, 0).join());
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.getAllDeadLetterMessages(10, 0).join());
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.getDeadLetterMessage(1L).join());
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.getStatistics().join());
        } finally {
            renameDeadLetterTableBack("dead_letter_queue_tmp");
        }
    }

    @Test
    void testAsyncWriteApisCompleteExceptionallyWhenDeadLetterTableUnavailable() {
        renameDeadLetterTable("dead_letter_queue_tmp");
        try {
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.reprocessDeadLetterMessage(1L, "reason").join());
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.deleteDeadLetterMessage(1L, "reason").join());
            assertThrows(CompletionException.class,
                () -> deadLetterQueueManager.cleanupOldMessages(1).join());
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
    void testGetDeadLetterMessagesMultipleCalls() {
        String topic = "test-topic-get-multiple";
        List<DeadLetterMessage> messages1 = getDeadLetterMessages(topic, 10, 0);
        assertNotNull(messages1);

        List<DeadLetterMessage> messages2 = getDeadLetterMessages(topic, 10, 0);
        assertNotNull(messages2);
    }

    private int countOutboxRowsByTopic(String topic) {
        try {
            return reactivePool.withConnection(connection ->
                connection.preparedQuery("SELECT COUNT(*) AS cnt FROM outbox WHERE topic = $1")
                    .execute(io.vertx.sqlclient.Tuple.of(topic))
                    .map(rows -> rows.iterator().next().getInteger("cnt"))
            ).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to count outbox rows", e);
        }
    }

    private void renameDeadLetterTable(String temporaryName) {
        try {
            reactivePool.withConnection(connection ->
                connection.query("ALTER TABLE dead_letter_queue RENAME TO " + temporaryName).execute().mapEmpty()
            ).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to rename dead_letter_queue to temporary name", e);
        }
    }

    private void renameDeadLetterTableBack(String temporaryName) {
        try {
            reactivePool.withConnection(connection ->
                connection.query("ALTER TABLE " + temporaryName + " RENAME TO dead_letter_queue").execute().mapEmpty()
            ).toCompletionStage().toCompletableFuture().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to restore dead_letter_queue table", e);
        }
    }

    private void moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                       Object payload, Instant originalCreatedAt, String failureReason,
                                       int retryCount, Map<String, String> headers, String correlationId,
                                       String messageGroup) {
        deadLetterQueueManager
            .moveToDeadLetterQueue(originalTable, originalId, topic, payload, originalCreatedAt,
                failureReason, retryCount, headers, correlationId, messageGroup)
            .join();
    }

    private List<DeadLetterMessage> getDeadLetterMessages(String topic, int limit, int offset) {
        return deadLetterQueueManager.fetchDeadLetterMessagesByTopic(topic, limit, offset)
            .toCompletionStage().toCompletableFuture().join();
    }

    private List<DeadLetterMessage> getAllDeadLetterMessages(int limit, int offset) {
        return deadLetterQueueManager.fetchAllDeadLetterMessages(limit, offset)
            .toCompletionStage().toCompletableFuture().join();
    }

    private Optional<DeadLetterMessage> getDeadLetterMessage(long id) {
        return deadLetterQueueManager.fetchDeadLetterMessage(id)
            .toCompletionStage().toCompletableFuture().join();
    }

    private boolean reprocessDeadLetterMessage(long id, String reason) {
        return deadLetterQueueManager.reprocessDeadLetterMessageRecord(id, reason)
            .toCompletionStage().toCompletableFuture().join();
    }

    private boolean deleteDeadLetterMessage(long id, String reason) {
        return deadLetterQueueManager.removeDeadLetterMessage(id, reason)
            .toCompletionStage().toCompletableFuture().join();
    }

    private DeadLetterQueueStats getStatistics() {
        return deadLetterQueueManager.fetchStatistics()
            .toCompletionStage().toCompletableFuture().join();
    }

    private int cleanupOldMessages(int retentionDays) {
        return deadLetterQueueManager.purgeOldDeadLetterMessages(retentionDays)
            .toCompletionStage().toCompletableFuture().join();
    }
}
