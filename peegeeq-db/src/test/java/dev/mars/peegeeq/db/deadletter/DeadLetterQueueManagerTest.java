package dev.mars.peegeeq.db.deadletter;

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


import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.peegeeq.db.SharedPostgresExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DeadLetterQueueManager.
 *
 * This class is part of the PeeGeeQ message queue system, providing
 * production-ready PostgreSQL-based message queuing capabilities.
 *
 * <p><strong>IMPORTANT:</strong> This test uses SharedPostgresExtension for shared container.
 * Schema is initialized once by the extension. Tests use @ResourceLock to prevent data conflicts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@ExtendWith(SharedPostgresExtension.class)
@ResourceLock("dead-letter-queue-data")
class DeadLetterQueueManagerTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private DeadLetterQueueManager dlqManager;
    private ObjectMapper objectMapper;
    private Vertx vertx;

    @BeforeEach
    void setUp() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();
        vertx = Vertx.vertx();
        connectionManager = new PgConnectionManager(vertx);
        objectMapper = new ObjectMapper();

        PgConnectionConfig connectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig poolConfig = new PgPoolConfig.Builder()
                .minimumIdle(2)
                .maximumPoolSize(5)
                .build();

        reactivePool = connectionManager.getOrCreateReactivePool("test", connectionConfig, poolConfig);

        dlqManager = new DeadLetterQueueManager(reactivePool, objectMapper);

        // Clean up any existing data from previous tests to ensure test isolation
        // DO NOT recreate tables - they are created once by SharedPostgresExtension
        cleanupTestData();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Clean up test data after each test
        try {
            if (reactivePool != null) {
                cleanupTestData();
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to cleanup test data in tearDown: " + e.getMessage());
        }

        if (connectionManager != null) {
            connectionManager.close();
        }
        if (vertx != null) {
            vertx.close();
        }
    }

    /**
     * Cleans up test data to ensure test isolation.
     * This removes all data from test tables between test methods.
     */
    private void cleanupTestData() {
        try {
            reactivePool.withConnection(connection -> {
                // Clean up all test data from tables
                return connection.query("DELETE FROM dead_letter_queue").execute()
                    .compose(result -> connection.query("DELETE FROM outbox").execute())
                    .compose(result -> connection.query("DELETE FROM queue_messages").execute());
            }).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);

            System.out.println("DEBUG: Cleaned up test data for test isolation");
        } catch (Exception e) {
            System.err.println("Warning: Failed to cleanup test data: " + e.getMessage());
            // Don't throw - allow test to proceed
        }
    }

    @Test
    void testDeadLetterQueueManagerInitialization() {
        assertNotNull(dlqManager);
        
        // Initially, DLQ should be empty
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        assertTrue(stats.isEmpty());
        assertEquals(0, stats.getTotalMessages());
    }

    /**
     * Tests moving a message to the dead letter queue after processing failures.
     * This test verifies that failed messages are properly stored in the dead letter queue
     * with all necessary metadata for later analysis and potential reprocessing.
     *
     * INTENTIONAL FAILURE TEST: This test simulates a message processing failure
     * by moving a message to the dead letter queue with a failure reason.
     */
    @Test
    void testMoveMessageToDeadLetterQueue() {
        System.out.println("🧪 ===== RUNNING INTENTIONAL MESSAGE FAILURE DEAD LETTER QUEUE TEST ===== 🧪");
        System.out.println("🔥 **INTENTIONAL TEST** 🔥 This test simulates a message processing failure and moves it to dead letter queue");

        Map<String, String> headers = createTestHeaders();
        Instant createdAt = Instant.now().minusSeconds(300);

        System.out.println("🔥 **INTENTIONAL TEST FAILURE** 🔥 Moving message to dead letter queue due to simulated processing failure");
        dlqManager.moveToDeadLetterQueue(
            "outbox",
            123L,
            "test-topic",
            "{\"message\": \"test payload\"}",
            createdAt,
            "Test failure reason",
            3,
            headers,
            "correlation-123",
            "test-group"
        );

        // Verify the message was added
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        System.out.println("DEBUG: Stats total messages = " + stats.getTotalMessages());
        assertEquals(1, stats.getTotalMessages());
        assertEquals(1, stats.getUniqueTopics());
        assertEquals(1, stats.getUniqueTables());
        assertEquals(3.0, stats.getAverageRetryCount());

        System.out.println("✅ **SUCCESS** ✅ Failed message was properly moved to dead letter queue");
        System.out.println("🧪 ===== INTENTIONAL FAILURE TEST COMPLETED ===== 🧪");
    }

    @Test
    void testGetDeadLetterMessagesByTopic() {
        // Add multiple messages with different topics
        addTestDeadLetterMessage("topic1", "outbox", 1L);
        addTestDeadLetterMessage("topic1", "outbox", 2L);
        addTestDeadLetterMessage("topic2", "queue_messages", 3L);
        
        // Retrieve messages for topic1
        List<DeadLetterMessage> topic1Messages = dlqManager.getDeadLetterMessages("topic1", 10, 0);
        assertEquals(2, topic1Messages.size());
        
        for (DeadLetterMessage msg : topic1Messages) {
            assertEquals("topic1", msg.getTopic());
        }
        
        // Retrieve messages for topic2
        List<DeadLetterMessage> topic2Messages = dlqManager.getDeadLetterMessages("topic2", 10, 0);
        assertEquals(1, topic2Messages.size());
        assertEquals("topic2", topic2Messages.get(0).getTopic());
    }

    @Test
    void testGetAllDeadLetterMessages() {
        // Add multiple messages
        addTestDeadLetterMessage("topic1", "outbox", 1L);
        addTestDeadLetterMessage("topic2", "outbox", 2L);
        addTestDeadLetterMessage("topic3", "queue_messages", 3L);
        
        // Retrieve all messages
        List<DeadLetterMessage> allMessages = dlqManager.getAllDeadLetterMessages(10, 0);
        assertEquals(3, allMessages.size());
        
        // Test pagination
        List<DeadLetterMessage> firstPage = dlqManager.getAllDeadLetterMessages(2, 0);
        assertEquals(2, firstPage.size());
        
        List<DeadLetterMessage> secondPage = dlqManager.getAllDeadLetterMessages(2, 2);
        assertEquals(1, secondPage.size());
    }

    @Test
    void testGetSpecificDeadLetterMessage() {
        addTestDeadLetterMessage("test-topic", "outbox", 123L);
        
        List<DeadLetterMessage> messages = dlqManager.getAllDeadLetterMessages(1, 0);
        assertFalse(messages.isEmpty());
        
        long messageId = messages.get(0).getId();
        
        Optional<DeadLetterMessage> retrieved = dlqManager.getDeadLetterMessage(messageId);
        assertTrue(retrieved.isPresent());
        
        DeadLetterMessage message = retrieved.get();
        assertEquals("test-topic", message.getTopic());
        assertEquals("outbox", message.getOriginalTable());
        assertEquals(123L, message.getOriginalId());
        assertEquals("Test failure reason", message.getFailureReason());
        assertEquals(3, message.getRetryCount());
    }

    @Test
    void testGetNonExistentDeadLetterMessage() {
        // ===== RUNNING INTENTIONAL NON-EXISTENT MESSAGE TEST =====
        // **INTENTIONAL TEST** - This test deliberately queries for a non-existent dead letter message
        // to verify proper handling of missing records
        Optional<DeadLetterMessage> nonExistent = dlqManager.getDeadLetterMessage(99999L);
        assertFalse(nonExistent.isPresent());
        // **SUCCESS** - Non-existent message properly returned empty Optional
        // ===== INTENTIONAL TEST COMPLETED =====
    }

    @Test
    void testReprocessDeadLetterMessage() {
        // First, add a message to the dead letter queue
        addTestDeadLetterMessage("test-topic", "outbox", 123L);
        
        List<DeadLetterMessage> messages = dlqManager.getAllDeadLetterMessages(1, 0);
        assertFalse(messages.isEmpty());
        
        long dlqMessageId = messages.get(0).getId();
        
        // Reprocess the message
        boolean success = dlqManager.reprocessDeadLetterMessage(dlqMessageId, "Manual reprocessing");
        assertTrue(success);
        
        // Verify the message was removed from DLQ
        Optional<DeadLetterMessage> shouldBeEmpty = dlqManager.getDeadLetterMessage(dlqMessageId);
        assertFalse(shouldBeEmpty.isPresent());
        
        // Verify the message was added back to the original table
        verifyMessageInOriginalTable("outbox", "test-topic");
    }

    @Test
    void testReprocessNonExistentMessage() {
        System.out.println("🔍 ===== RUNNING INTENTIONAL NON-EXISTENT MESSAGE REPROCESS TEST =====");
        System.out.println("🔍 **INTENTIONAL TEST** - This test deliberately attempts to reprocess a non-existent dead letter message");
        System.out.println("🔍 **INTENTIONAL TEST FAILURE** - Expected warning: 'Dead letter message not found: 99999'");

        boolean result = dlqManager.reprocessDeadLetterMessage(99999L, "Non-existent message");
        assertFalse(result);

        System.out.println("🔍 **SUCCESS** - Non-existent message reprocess properly returned false");
        System.out.println("🔍 ===== INTENTIONAL TEST COMPLETED =====");
    }

    @Test
    void testDeleteDeadLetterMessage() {
        addTestDeadLetterMessage("test-topic", "outbox", 123L);
        
        List<DeadLetterMessage> messages = dlqManager.getAllDeadLetterMessages(1, 0);
        assertFalse(messages.isEmpty());
        
        long messageId = messages.get(0).getId();
        
        // Delete the message
        boolean success = dlqManager.deleteDeadLetterMessage(messageId, "Manual deletion");
        assertTrue(success);
        
        // Verify the message was deleted
        Optional<DeadLetterMessage> shouldBeEmpty = dlqManager.getDeadLetterMessage(messageId);
        assertFalse(shouldBeEmpty.isPresent());
        
        // Verify statistics are updated
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        assertEquals(0, stats.getTotalMessages());
    }

    @Test
    void testDeleteNonExistentMessage() {
        System.out.println("🗑️ ===== RUNNING INTENTIONAL NON-EXISTENT MESSAGE DELETION TEST =====");
        System.out.println("🗑️ **INTENTIONAL TEST** - This test deliberately attempts to delete a non-existent dead letter message");
        System.out.println("🗑️ **INTENTIONAL TEST FAILURE** - Expected warning: 'Dead letter message not found for deletion: 99999'");

        boolean result = dlqManager.deleteDeadLetterMessage(99999L, "Non-existent message");
        assertFalse(result);

        System.out.println("🗑️ **SUCCESS** - Non-existent message deletion properly returned false");
        System.out.println("🗑️ ===== INTENTIONAL TEST COMPLETED =====");
    }

    @Test
    void testDeadLetterQueueStatistics() {
        // Add messages with different characteristics
        addTestDeadLetterMessage("topic1", "outbox", 1L);
        addTestDeadLetterMessage("topic2", "outbox", 2L);
        addTestDeadLetterMessage("topic1", "queue_messages", 3L);
        
        // Add a message with different retry count
        Map<String, String> headers = createTestHeaders();
        dlqManager.moveToDeadLetterQueue(
            "outbox", 4L, "topic3", "{\"test\": \"data\"}", 
            Instant.now().minusSeconds(100), "Different failure", 5, 
            headers, "corr-4", "group-4"
        );
        
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        assertEquals(4, stats.getTotalMessages());
        assertEquals(3, stats.getUniqueTopics()); // topic1, topic2, topic3
        assertEquals(2, stats.getUniqueTables()); // outbox, queue_messages
        assertEquals(3.5, stats.getAverageRetryCount()); // (3+3+3+5)/4 = 3.5
        assertNotNull(stats.getOldestFailure());
        assertNotNull(stats.getNewestFailure());
        assertFalse(stats.isEmpty());
    }

    @Test
    void testCleanupOldMessages() throws InterruptedException {
        // Add some messages
        addTestDeadLetterMessage("topic1", "outbox", 1L);
        addTestDeadLetterMessage("topic2", "outbox", 2L);
        
        // Verify messages exist
        DeadLetterQueueStats beforeCleanup = dlqManager.getStatistics();
        assertEquals(2, beforeCleanup.getTotalMessages());
        
        // Cleanup with very short retention (should delete all messages)
        int deletedCount = dlqManager.cleanupOldMessages(0);
        assertEquals(2, deletedCount);
        
        // Verify messages were deleted
        DeadLetterQueueStats afterCleanup = dlqManager.getStatistics();
        assertEquals(0, afterCleanup.getTotalMessages());
    }

    @Test
    void testCleanupWithNoOldMessages() {
        // Add a recent message
        addTestDeadLetterMessage("topic1", "outbox", 1L);
        
        // Cleanup with long retention (should not delete anything)
        int deletedCount = dlqManager.cleanupOldMessages(30);
        assertEquals(0, deletedCount);
        
        // Verify message still exists
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        assertEquals(1, stats.getTotalMessages());
    }

    @Test
    void testDeadLetterMessageEquality() {
        DeadLetterMessage msg1 = new DeadLetterMessage(
            1L, "outbox", 123L, "topic", "{\"test\": \"data\"}", 
            Instant.now(), Instant.now(), "failure", 3, 
            Map.of("key", "value"), "corr-1", "group-1"
        );
        
        DeadLetterMessage msg2 = new DeadLetterMessage(
            1L, "outbox", 123L, "topic", "{\"test\": \"data\"}", 
            msg1.getOriginalCreatedAt(), msg1.getFailedAt(), "failure", 3, 
            Map.of("key", "value"), "corr-1", "group-1"
        );
        
        DeadLetterMessage msg3 = new DeadLetterMessage(
            2L, "outbox", 123L, "topic", "{\"test\": \"data\"}", 
            Instant.now(), Instant.now(), "failure", 3, 
            Map.of("key", "value"), "corr-1", "group-1"
        );
        
        assertEquals(msg1, msg2);
        assertNotEquals(msg1, msg3);
        assertEquals(msg1.hashCode(), msg2.hashCode());
        assertNotEquals(msg1.hashCode(), msg3.hashCode());
    }

    @Test
    void testDeadLetterMessageToString() {
        DeadLetterMessage message = new DeadLetterMessage(
            1L, "outbox", 123L, "test-topic", "{\"test\": \"data\"}", 
            Instant.now(), Instant.now(), "Test failure", 3, 
            Map.of("key", "value"), "corr-1", "group-1"
        );
        
        String messageString = message.toString();
        assertNotNull(messageString);
        assertTrue(messageString.contains("id=1"));
        assertTrue(messageString.contains("topic='test-topic'"));
        assertTrue(messageString.contains("originalTable='outbox'"));
        assertTrue(messageString.contains("failureReason='Test failure'"));
    }

    @Test
    void testDeadLetterQueueStatsEquality() {
        Instant now = Instant.now();
        
        DeadLetterQueueStats stats1 = new DeadLetterQueueStats(5, 3, 2, now, now, 2.5);
        DeadLetterQueueStats stats2 = new DeadLetterQueueStats(5, 3, 2, now, now, 2.5);
        DeadLetterQueueStats stats3 = new DeadLetterQueueStats(6, 3, 2, now, now, 2.5);
        
        assertEquals(stats1, stats2);
        assertNotEquals(stats1, stats3);
        assertEquals(stats1.hashCode(), stats2.hashCode());
    }

    @Test
    void testDeadLetterQueueStatsToString() {
        DeadLetterQueueStats stats = new DeadLetterQueueStats(
            10, 5, 3, Instant.now(), Instant.now(), 2.75
        );
        
        String statsString = stats.toString();
        assertNotNull(statsString);
        assertTrue(statsString.contains("totalMessages=10"));
        assertTrue(statsString.contains("uniqueTopics=5"));
        assertTrue(statsString.contains("uniqueTables=3"));
        assertTrue(statsString.contains("averageRetryCount=2.75"));
    }

    @Test
    void testConcurrentDeadLetterOperations() throws InterruptedException {
        int threadCount = 5;
        int messagesPerThread = 10;
        
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < messagesPerThread; j++) {
                    addTestDeadLetterMessage("topic-" + threadId, "outbox", threadId * 1000L + j);
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify all messages were added
        DeadLetterQueueStats stats = dlqManager.getStatistics();
        assertEquals(threadCount * messagesPerThread, stats.getTotalMessages());
        assertEquals(threadCount, stats.getUniqueTopics());
    }

    private void addTestDeadLetterMessage(String topic, String originalTable, long originalId) {
        Map<String, String> headers = createTestHeaders();
        dlqManager.moveToDeadLetterQueue(
            originalTable,
            originalId,
            topic,
            "{\"message\": \"test data\", \"id\": " + originalId + "}",
            Instant.now().minusSeconds(300),
            "Test failure reason",
            3,
            headers,
            "correlation-" + originalId,
            "test-group"
        );
    }

    private Map<String, String> createTestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("source", "test");
        headers.put("version", "1.0");
        return headers;
    }

    private void verifyMessageInOriginalTable(String tableName, String expectedTopic) {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE topic = $1";

        try {
            Integer count = reactivePool.withConnection(connection -> {
                return connection.preparedQuery(sql)
                    .execute(io.vertx.sqlclient.Tuple.of(expectedTopic))
                    .map(rowSet -> {
                        if (rowSet.iterator().hasNext()) {
                            return rowSet.iterator().next().getInteger(0);
                        }
                        return 0;
                    });
            }).toCompletionStage().toCompletableFuture().get();

            assertTrue(count > 0, "Message should exist in original table");
        } catch (Exception e) {
            throw new RuntimeException("Failed to verify message in original table", e);
        }
    }

    @Test
    void testReactiveDeadLetterQueueManager() {
        PostgreSQLContainer<?> postgres = SharedPostgresExtension.getContainer();

        // Create connection config for reactive pool
        PgConnectionConfig reactiveConnectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig reactivePoolConfig = new PgPoolConfig.Builder()
                .minimumIdle(2)
                .maximumPoolSize(5)
                .build();

        // Create reactive pool
        Pool reactivePool = connectionManager.getOrCreateReactivePool("test-reactive", reactiveConnectionConfig, reactivePoolConfig);
        assertNotNull(reactivePool);

        // Create dead letter queue manager with reactive constructor
        DeadLetterQueueManager reactiveDlqManager = new DeadLetterQueueManager(reactivePool, objectMapper);
        assertNotNull(reactiveDlqManager);

        // Test basic functionality - move a message to dead letter queue
        String originalTable = "outbox";
        long originalId = 12345L;
        String topic = "test.reactive.topic";
        Map<String, Object> payload = Map.of("message", "test reactive payload", "id", 1);
        Instant originalCreatedAt = Instant.now();
        String failureReason = "Test reactive failure";
        int retryCount = 3;
        Map<String, String> headers = Map.of("header1", "value1");
        String correlationId = "reactive-correlation-123";
        String messageGroup = "reactive-group";

        // This should work without throwing an exception
        assertDoesNotThrow(() -> {
            reactiveDlqManager.moveToDeadLetterQueue(originalTable, originalId, topic, payload,
                originalCreatedAt, failureReason, retryCount, headers, correlationId, messageGroup);
        });

        // Verify the message was inserted by checking with the legacy manager
        List<DeadLetterMessage> messages = dlqManager.getDeadLetterMessages(topic, 10, 0);
        assertFalse(messages.isEmpty());

        DeadLetterMessage message = messages.get(0);
        assertEquals(originalTable, message.getOriginalTable());
        assertEquals(originalId, message.getOriginalId());
        assertEquals(topic, message.getTopic());
        assertEquals(failureReason, message.getFailureReason());
        assertEquals(retryCount, message.getRetryCount());
        assertEquals(correlationId, message.getCorrelationId());
        assertEquals(messageGroup, message.getMessageGroup());
    }
}
