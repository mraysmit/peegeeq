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
import dev.mars.peegeeq.db.SharedPostgresTestExtension;
import dev.mars.peegeeq.db.config.PgConnectionConfig;
import dev.mars.peegeeq.db.config.PgPoolConfig;
import dev.mars.peegeeq.db.connection.PgConnectionManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
 * <p><strong>IMPORTANT:</strong> This test uses SharedPostgresTestExtension for shared container.
 * Schema is initialized once by the extension. Tests use @ResourceLock to prevent data conflicts.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-13
 * @version 1.0
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith({SharedPostgresTestExtension.class, VertxExtension.class})
@ResourceLock(value = "dead-letter-queue-database", mode = org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class DeadLetterQueueManagerTest {

    private PgConnectionManager connectionManager;
    private Pool reactivePool;
    private DeadLetterQueueManager dlqManager;
    private ObjectMapper objectMapper;
    private Vertx vertx;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();
        this.vertx = vertx;
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
                .maxSize(5)
                .build();

        reactivePool = connectionManager.getOrCreateReactivePool("test", connectionConfig, poolConfig);

        dlqManager = new DeadLetterQueueManager(reactivePool, objectMapper);

        // Clean up any existing data from previous tests to ensure test isolation
        cleanupTestData()
            .onSuccess(v -> testContext.completeNow())
            .onFailure(v -> testContext.completeNow());
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        Future<Void> cleanup = (reactivePool != null)
            ? cleanupTestData().recover(e -> {
                System.err.println("Warning: Failed to cleanup test data in tearDown: " + e.getMessage());
                return Future.succeededFuture();
            })
            : Future.succeededFuture();

        cleanup.compose(v -> {
            if (connectionManager != null) {
                return connectionManager.closeAsync();
            }
            return Future.succeededFuture();
        })
        .onSuccess(v -> testContext.completeNow())
        .onFailure(testContext::failNow);
    }

    private Future<Void> cleanupTestData() {
        return reactivePool.withTransaction(connection ->
            connection.query("DELETE FROM dead_letter_queue").execute()
                .compose(result -> connection.query("DELETE FROM outbox").execute())
                .compose(result -> connection.query("DELETE FROM queue_messages").execute())
        ).mapEmpty();
    }

    @Test
    void testDeadLetterQueueManagerInitialization(VertxTestContext testContext) {
        assertNotNull(dlqManager);

        getStatistics()
            .onSuccess(stats -> testContext.verify(() -> {
                assertTrue(stats.isEmpty());
                assertEquals(0, stats.getTotalMessages());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
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
    void testMoveMessageToDeadLetterQueue(VertxTestContext testContext) {
        Map<String, String> headers = createTestHeaders();
        Instant createdAt = Instant.now().minusSeconds(300);

        moveToDeadLetterQueue("outbox", 123L, "test-topic", "{\"message\": \"test payload\"}",
                createdAt, "Test failure reason", 3, headers, "correlation-123", "test-group")
            .compose(v -> getStatistics())
            .onSuccess(stats -> testContext.verify(() -> {
                assertEquals(1, stats.getTotalMessages());
                assertEquals(1, stats.getUniqueTopics());
                assertEquals(1, stats.getUniqueTables());
                assertEquals(3.0, stats.getAverageRetryCount());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetDeadLetterMessagesByTopic(VertxTestContext testContext) {
        addTestDeadLetterMessage("topic1", "outbox", 1L)
            .compose(v -> addTestDeadLetterMessage("topic1", "outbox", 2L))
            .compose(v -> addTestDeadLetterMessage("topic2", "queue_messages", 3L))
            .compose(v -> getDeadLetterMessages("topic1", 10, 0))
            .compose(topic1Messages -> {
                assertEquals(2, topic1Messages.size());
                for (DeadLetterMessage msg : topic1Messages) {
                    assertEquals("topic1", msg.getTopic());
                }
                return getDeadLetterMessages("topic2", 10, 0);
            })
            .onSuccess(topic2Messages -> testContext.verify(() -> {
                assertEquals(1, topic2Messages.size());
                assertEquals("topic2", topic2Messages.get(0).getTopic());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetAllDeadLetterMessages(VertxTestContext testContext) {
        addTestDeadLetterMessage("topic1", "outbox", 1L)
            .compose(v -> addTestDeadLetterMessage("topic2", "outbox", 2L))
            .compose(v -> addTestDeadLetterMessage("topic3", "queue_messages", 3L))
            .compose(v -> getAllDeadLetterMessages(10, 0))
            .compose(allMessages -> {
                assertEquals(3, allMessages.size());
                return getAllDeadLetterMessages(2, 0);
            })
            .compose(firstPage -> {
                assertEquals(2, firstPage.size());
                return getAllDeadLetterMessages(2, 2);
            })
            .onSuccess(secondPage -> testContext.verify(() -> {
                assertEquals(1, secondPage.size());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetSpecificDeadLetterMessage(VertxTestContext testContext) {
        addTestDeadLetterMessage("test-topic", "outbox", 123L)
            .compose(v -> getAllDeadLetterMessages(1, 0))
            .compose(messages -> {
                assertFalse(messages.isEmpty());
                long messageId = messages.get(0).getId();
                return getDeadLetterMessage(messageId);
            })
            .onSuccess(retrieved -> testContext.verify(() -> {
                assertTrue(retrieved.isPresent());
                DeadLetterMessage message = retrieved.get();
                assertEquals("test-topic", message.getTopic());
                assertEquals("outbox", message.getOriginalTable());
                assertEquals(123L, message.getOriginalId());
                assertEquals("Test failure reason", message.getFailureReason());
                assertEquals(3, message.getRetryCount());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testGetNonExistentDeadLetterMessage(VertxTestContext testContext) {
        getDeadLetterMessage(99999L)
            .onSuccess(nonExistent -> testContext.verify(() -> {
                assertFalse(nonExistent.isPresent());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testReprocessDeadLetterMessage(VertxTestContext testContext) {
        addTestDeadLetterMessage("test-topic", "outbox", 123L)
            .compose(v -> getAllDeadLetterMessages(1, 0))
            .compose(messages -> {
                assertFalse(messages.isEmpty());
                long dlqMessageId = messages.get(0).getId();
                return reprocessDeadLetterMessage(dlqMessageId, "Manual reprocessing")
                    .compose(success -> {
                        assertTrue(success);
                        return getDeadLetterMessage(dlqMessageId);
                    })
                    .compose(shouldBeEmpty -> {
                        assertFalse(shouldBeEmpty.isPresent());
                        return verifyMessageInOriginalTable("outbox", "test-topic");
                    });
            })
            .onSuccess(count -> testContext.verify(() -> {
                assertTrue(count > 0, "Message should exist in original table");
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testReprocessNonExistentMessage(VertxTestContext testContext) {
        reprocessDeadLetterMessage(99999L, "Non-existent message")
            .onSuccess(result -> testContext.verify(() -> {
                assertFalse(result);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeleteDeadLetterMessage(VertxTestContext testContext) {
        addTestDeadLetterMessage("test-topic", "outbox", 123L)
            .compose(v -> getAllDeadLetterMessages(1, 0))
            .compose(messages -> {
                assertFalse(messages.isEmpty());
                long messageId = messages.get(0).getId();
                return deleteDeadLetterMessage(messageId, "Manual deletion")
                    .compose(success -> {
                        assertTrue(success);
                        return getDeadLetterMessage(messageId);
                    })
                    .compose(shouldBeEmpty -> {
                        assertFalse(shouldBeEmpty.isPresent());
                        return getStatistics();
                    });
            })
            .onSuccess(stats -> testContext.verify(() -> {
                assertEquals(0, stats.getTotalMessages());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeleteNonExistentMessage(VertxTestContext testContext) {
        deleteDeadLetterMessage(99999L, "Non-existent message")
            .onSuccess(result -> testContext.verify(() -> {
                assertFalse(result);
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testDeadLetterQueueStatistics(VertxTestContext testContext) {
        Map<String, String> headers = createTestHeaders();
        addTestDeadLetterMessage("topic1", "outbox", 1L)
            .compose(v -> addTestDeadLetterMessage("topic2", "outbox", 2L))
            .compose(v -> addTestDeadLetterMessage("topic1", "queue_messages", 3L))
            .compose(v -> moveToDeadLetterQueue(
                "outbox", 4L, "topic3", "{\"test\": \"data\"}",
                Instant.now().minusSeconds(100), "Different failure", 5,
                headers, "corr-4", "group-4"
            ))
            .compose(v -> getStatistics())
            .onSuccess(stats -> testContext.verify(() -> {
                assertNotNull(stats, "Statistics should not be null");
                assertEquals(4, stats.getTotalMessages());
                assertEquals(3, stats.getUniqueTopics());
                assertEquals(2, stats.getUniqueTables());
                assertEquals(3.5, stats.getAverageRetryCount());
                assertNotNull(stats.getOldestFailure());
                assertNotNull(stats.getNewestFailure());
                assertFalse(stats.isEmpty());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCleanupOldMessages(VertxTestContext testContext) {
        addTestDeadLetterMessage("topic1", "outbox", 1L)
            .compose(v -> addTestDeadLetterMessage("topic2", "outbox", 2L))
            .compose(v -> reactivePool.withConnection(connection ->
                connection.query("UPDATE dead_letter_queue SET failed_at = NOW() - INTERVAL '2 days'")
                    .execute()
                    .mapEmpty()
            ))
            .compose(v -> getStatistics())
            .compose(beforeCleanup -> {
                assertEquals(2, beforeCleanup.getTotalMessages());
                return cleanupOldMessages(1);
            })
            .compose(deletedCount -> {
                assertEquals(2, (int) deletedCount);
                return getStatistics();
            })
            .onSuccess(afterCleanup -> testContext.verify(() -> {
                assertEquals(0, afterCleanup.getTotalMessages());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    @Test
    void testCleanupWithNoOldMessages(VertxTestContext testContext) {
        addTestDeadLetterMessage("topic1", "outbox", 1L)
            .compose(v -> cleanupOldMessages(30))
            .compose(deletedCount -> {
                assertEquals(0, (int) deletedCount);
                return getStatistics();
            })
            .onSuccess(stats -> testContext.verify(() -> {
                assertEquals(1, stats.getTotalMessages());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
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
    void testConcurrentDeadLetterOperations(VertxTestContext testContext) {
        int topicCount = 5;
        int messagesPerTopic = 10;
        int expectedMessages = topicCount * messagesPerTopic;

        // Build all futures concurrently using Future.all()
        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < topicCount; i++) {
            for (int j = 0; j < messagesPerTopic; j++) {
                futures.add(addTestDeadLetterMessage("topic-" + i, "outbox", i * 1000L + j));
            }
        }

        Future.all(futures)
            .compose(cf -> getStatistics())
            .onSuccess(stats -> testContext.verify(() -> {
                assertNotNull(stats, "Statistics should not be null");
                assertEquals(expectedMessages, stats.getTotalMessages());
                assertEquals(topicCount, stats.getUniqueTopics());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }

    private Future<Void> addTestDeadLetterMessage(String topic, String originalTable, long originalId) {
        Map<String, String> headers = createTestHeaders();
        return moveToDeadLetterQueue(
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

    private Future<Void> moveToDeadLetterQueue(String originalTable, long originalId, String topic,
                                       Object payload, Instant originalCreatedAt, String failureReason,
                                       int retryCount, Map<String, String> headers, String correlationId,
                                       String messageGroup) {
        return dlqManager.moveToDeadLetterQueue(originalTable, originalId, topic, payload, originalCreatedAt,
            failureReason, retryCount, headers, correlationId, messageGroup);
    }

    private Future<List<DeadLetterMessage>> getDeadLetterMessages(String topic, int limit, int offset) {
        return dlqManager.fetchDeadLetterMessagesByTopic(topic, limit, offset);
    }

    private Future<List<DeadLetterMessage>> getAllDeadLetterMessages(int limit, int offset) {
        return dlqManager.fetchAllDeadLetterMessages(limit, offset);
    }

    private Future<Optional<DeadLetterMessage>> getDeadLetterMessage(long id) {
        return dlqManager.fetchDeadLetterMessage(id);
    }

    private Future<Boolean> reprocessDeadLetterMessage(long id, String reason) {
        return dlqManager.reprocessDeadLetterMessageRecord(id, reason);
    }

    private Future<Boolean> deleteDeadLetterMessage(long id, String reason) {
        return dlqManager.removeDeadLetterMessage(id, reason);
    }

    private Future<DeadLetterQueueStats> getStatistics() {
        return dlqManager.fetchStatistics();
    }

    private Future<Integer> cleanupOldMessages(int retentionDays) {
        return dlqManager.purgeOldDeadLetterMessages(retentionDays);
    }

    private Future<Integer> verifyMessageInOriginalTable(String tableName, String expectedTopic) {
        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE topic = $1";
        return reactivePool.withConnection(connection ->
            connection.preparedQuery(sql)
                .execute(io.vertx.sqlclient.Tuple.of(expectedTopic))
                .map(rowSet -> {
                    if (rowSet.iterator().hasNext()) {
                        return rowSet.iterator().next().getInteger(0);
                    }
                    return 0;
                })
        );
    }

    @Test
    void testReactiveDeadLetterQueueManager(VertxTestContext testContext) {
        PostgreSQLContainer postgres = SharedPostgresTestExtension.getContainer();

        PgConnectionConfig reactiveConnectionConfig = new PgConnectionConfig.Builder()
                .host(postgres.getHost())
                .port(postgres.getFirstMappedPort())
                .database(postgres.getDatabaseName())
                .username(postgres.getUsername())
                .password(postgres.getPassword())
                .build();

        PgPoolConfig reactivePoolConfig = new PgPoolConfig.Builder()
                .maxSize(5)
                .build();

        Pool reactivePool = connectionManager.getOrCreateReactivePool("test-reactive", reactiveConnectionConfig, reactivePoolConfig);
        assertNotNull(reactivePool);

        DeadLetterQueueManager reactiveDlqManager = new DeadLetterQueueManager(reactivePool, objectMapper);
        assertNotNull(reactiveDlqManager);

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

        reactiveDlqManager.moveToDeadLetterQueue(originalTable, originalId, topic, payload,
                originalCreatedAt, failureReason, retryCount, headers, correlationId, messageGroup)
            .compose(v -> getDeadLetterMessages(topic, 10, 0))
            .onSuccess(messages -> testContext.verify(() -> {
                assertFalse(messages.isEmpty());
                DeadLetterMessage message = messages.get(0);
                assertEquals(originalTable, message.getOriginalTable());
                assertEquals(originalId, message.getOriginalId());
                assertEquals(topic, message.getTopic());
                assertEquals(failureReason, message.getFailureReason());
                assertEquals(retryCount, message.getRetryCount());
                assertEquals(correlationId, message.getCorrelationId());
                assertEquals(messageGroup, message.getMessageGroup());
                testContext.completeNow();
            }))
            .onFailure(testContext::failNow);
    }
}
