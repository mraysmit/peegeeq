package dev.mars.peegeeq.outbox.deadletter;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for LoggingDeadLetterQueue.
 */
@Tag(TestCategories.CORE)
class LoggingDeadLetterQueueCoreTest {

    private LoggingDeadLetterQueue deadLetterQueue;
    private static final String TEST_TOPIC = "test-dlq-topic";

    @BeforeEach
    void setUp() {
        deadLetterQueue = new LoggingDeadLetterQueue(TEST_TOPIC);
    }

    @Test
    void testCreation() {
        assertNotNull(deadLetterQueue);
        assertEquals(TEST_TOPIC, deadLetterQueue.getDeadLetterTopic());
    }

    @Test
    void testGetDeadLetterTopic() {
        assertEquals(TEST_TOPIC, deadLetterQueue.getDeadLetterTopic());
    }

    @Test
    void testSendToDeadLetter_Success() throws Exception {
        Message<String> message = createTestMessage("test-id-1", "test payload");
        String reason = "Test failure reason";
        int attempts = 3;
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        CompletableFuture<Void> future = deadLetterQueue.sendToDeadLetter(
            message, reason, attempts, metadata);

        assertNotNull(future);
        future.get(5, TimeUnit.SECONDS); // Should complete successfully

        // Verify metrics updated
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNotNull(metrics.getLastSentTime());
        assertNull(metrics.getLastFailureTime());
    }

    @Test
    void testSendToDeadLetter_MultipleSends() throws Exception {
        for (int i = 0; i < 5; i++) {
            Message<String> message = createTestMessage("test-id-" + i, "payload " + i);
            CompletableFuture<Void> future = deadLetterQueue.sendToDeadLetter(
                message, "Reason " + i, i, new HashMap<>());
            future.get(5, TimeUnit.SECONDS);
        }

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(5, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
    }

    @Test
    void testSendToDeadLetter_WithNullMetadata() throws Exception {
        Message<String> message = createTestMessage("test-id-null", "test payload");
        
        CompletableFuture<Void> future = deadLetterQueue.sendToDeadLetter(
            message, "Test reason", 1, null);

        assertNotNull(future);
        future.get(5, TimeUnit.SECONDS);

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    void testSendToDeadLetter_IntentionalTestFailure() throws Exception {
        Message<String> message = createTestMessage("test-id-intentional", "test payload");
        String reason = "INTENTIONAL TEST FAILURE - this should be logged differently";
        
        CompletableFuture<Void> future = deadLetterQueue.sendToDeadLetter(
            message, reason, 3, new HashMap<>());

        assertNotNull(future);
        future.get(5, TimeUnit.SECONDS);

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    void testGetMetrics_InitialState() {
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        
        assertNotNull(metrics);
        assertEquals(TEST_TOPIC, metrics.getTopic());
        assertEquals(0, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNull(metrics.getLastSentTime());
        assertNull(metrics.getLastFailureTime());
    }

    @Test
    void testClose() {
        assertDoesNotThrow(() -> deadLetterQueue.close());
    }

    @Test
    void testClose_AfterSends() throws Exception {
        Message<String> message = createTestMessage("test-id", "payload");
        deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>())
            .get(5, TimeUnit.SECONDS);

        assertDoesNotThrow(() -> deadLetterQueue.close());
    }

    @Test
    void testMetrics_SuccessRate() throws Exception {
        // Send 3 successful messages
        for (int i = 0; i < 3; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload");
            deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>())
                .get(5, TimeUnit.SECONDS);
        }

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1.0, metrics.getSuccessRate(), 0.001);
        assertNotNull(metrics.toString());
        assertTrue(metrics.toString().contains(TEST_TOPIC));
        assertTrue(metrics.toString().contains("sent=3"));
    }

    @Test
    void testMetrics_AllGetters() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>())
            .get(5, TimeUnit.SECONDS);

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();

        assertEquals(TEST_TOPIC, metrics.getTopic());
        assertEquals(1, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNotNull(metrics.getLastSentTime());
        assertNull(metrics.getLastFailureTime());
        assertEquals(1.0, metrics.getSuccessRate(), 0.001);
    }

    private Message<String> createTestMessage(String id, String payload) {
        return new Message<String>() {
            @Override
            public String getId() { return id; }

            @Override
            public String getPayload() { return payload; }

            @Override
            public Map<String, String> getHeaders() { return new HashMap<>(); }

            @Override
            public Instant getCreatedAt() { return Instant.now(); }
        };
    }
}

