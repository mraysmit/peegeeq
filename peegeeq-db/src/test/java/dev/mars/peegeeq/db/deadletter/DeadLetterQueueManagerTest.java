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
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.db.migration.SchemaMigrationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Testcontainers
class DeadLetterQueueManagerTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("dlq_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    private PgConnectionManager connectionManager;
    private DataSource dataSource;
    private DeadLetterQueueManager dlqManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws SQLException {
        connectionManager = new PgConnectionManager();
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

        dataSource = connectionManager.getOrCreateDataSource("test", connectionConfig, poolConfig);

        // Apply migrations to create necessary tables
        SchemaMigrationManager migrationManager = new SchemaMigrationManager(dataSource);
        migrationManager.migrate();

        // Clean up any existing data from previous tests
        cleanupDatabase();

        dlqManager = new DeadLetterQueueManager(dataSource, objectMapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    private void cleanupDatabase() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // Clean up dead letter queue table
            stmt.execute("DELETE FROM dead_letter_queue");
            // Clean up other tables that might have test data
            stmt.execute("DELETE FROM outbox");
            stmt.execute("DELETE FROM queue_messages");
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
        System.out.println("=== RUNNING INTENTIONAL MESSAGE FAILURE DEAD LETTER QUEUE TEST ===");
        System.out.println("This test simulates a message processing failure and moves it to dead letter queue");

        Map<String, String> headers = createTestHeaders();
        Instant createdAt = Instant.now().minusSeconds(300);

        System.out.println("INTENTIONAL FAILURE: Moving message to dead letter queue due to simulated processing failure");
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
        assertEquals(1, stats.getTotalMessages());
        assertEquals(1, stats.getUniqueTopics());
        assertEquals(1, stats.getUniqueTables());
        assertEquals(3.0, stats.getAverageRetryCount());

        System.out.println("SUCCESS: Failed message was properly moved to dead letter queue");
        System.out.println("=== INTENTIONAL FAILURE TEST COMPLETED ===");
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
        Optional<DeadLetterMessage> nonExistent = dlqManager.getDeadLetterMessage(99999L);
        assertFalse(nonExistent.isPresent());
    }

    @Test
    void testReprocessDeadLetterMessage() throws SQLException {
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
        boolean result = dlqManager.reprocessDeadLetterMessage(99999L, "Non-existent message");
        assertFalse(result);
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
        boolean result = dlqManager.deleteDeadLetterMessage(99999L, "Non-existent message");
        assertFalse(result);
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

    private void verifyMessageInOriginalTable(String tableName, String expectedTopic) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE topic = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, expectedTopic);
            try (var rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) > 0, "Message should exist in original table");
            }
        }
    }
}
