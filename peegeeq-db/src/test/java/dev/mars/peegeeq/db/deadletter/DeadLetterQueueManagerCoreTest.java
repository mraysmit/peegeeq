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
        assertDoesNotThrow(() -> deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 10, 0);
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
            deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 10, 0);
        assertEquals(5, messages.size());
    }

    @Test
    void testGetDeadLetterMessagesWithPagination() {
        // Add some test messages
        String topic = "test-topic-pagination";
        for (int i = 0; i < 10; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> page1 = deadLetterQueueManager.getDeadLetterMessages(topic, 5, 0);
        assertEquals(5, page1.size());

        // Retrieve second page
        List<DeadLetterMessage> page2 = deadLetterQueueManager.getDeadLetterMessages(topic, 5, 5);
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
            deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> allMessages = deadLetterQueueManager.getAllDeadLetterMessages(10, 0);
        assertTrue(allMessages.size() >= 3);
    }

    @Test
    void testGetDeadLetterMessage() {
        // Add a test message
        String topic = "test-topic-get";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
        long messageId = messages.get(0).getId();

        // Retrieve by ID
        Optional<DeadLetterMessage> retrieved = deadLetterQueueManager.getDeadLetterMessage(messageId);
        assertTrue(retrieved.isPresent());
        assertEquals(messageId, retrieved.get().getId());
        assertEquals(topic, retrieved.get().getTopic());
    }

    @Test
    void testGetDeadLetterMessageNotFound() {
        // Try to get a non-existent message
        Optional<DeadLetterMessage> retrieved = deadLetterQueueManager.getDeadLetterMessage(999999L);
        assertFalse(retrieved.isPresent());
    }

    @Test
    void testDeleteDeadLetterMessage() {
        // Add a test message
        String topic = "test-topic-delete";
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
        long messageId = messages.get(0).getId();

        // Delete the message
        boolean deleted = deadLetterQueueManager.deleteDeadLetterMessage(messageId, "Test deletion");
        assertTrue(deleted);

        // Verify message is gone
        Optional<DeadLetterMessage> retrieved = deadLetterQueueManager.getDeadLetterMessage(messageId);
        assertFalse(retrieved.isPresent());
    }

    @Test
    void testDeleteNonExistentMessage() {
        // Try to delete a non-existent message
        boolean deleted = deadLetterQueueManager.deleteDeadLetterMessage(999999L, "Test deletion");
        assertFalse(deleted);
    }

    @Test
    void testGetStatistics() {
        // Add some test messages
        for (int i = 0; i < 5; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("index", i);
            deadLetterQueueManager.moveToDeadLetterQueue(
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
        DeadLetterQueueStats stats = deadLetterQueueManager.getStatistics();
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
        assertDoesNotThrow(() -> deadLetterQueueManager.moveToDeadLetterQueue(
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
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 1, 0);
        assertEquals(1, messages.size());
    }

    @Test
    void testGetDeadLetterMessagesWithLargeLimit() {
        String topic = "test-topic-large-limit";
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 1000, 0);
        assertNotNull(messages);
    }

    @Test
    void testGetDeadLetterMessagesWithLargeOffset() {
        String topic = "test-topic-large-offset";
        List<DeadLetterMessage> messages = deadLetterQueueManager.getDeadLetterMessages(topic, 10, 1000);
        assertNotNull(messages);
    }

    @Test
    void testGetDeadLetterMessagesMultipleCalls() {
        String topic = "test-topic-get-multiple";
        List<DeadLetterMessage> messages1 = deadLetterQueueManager.getDeadLetterMessages(topic, 10, 0);
        assertNotNull(messages1);

        List<DeadLetterMessage> messages2 = deadLetterQueueManager.getDeadLetterMessages(topic, 10, 0);
        assertNotNull(messages2);
    }
}
