package dev.mars.peegeeq.outbox.deadletter;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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

        Future<Void> future = deadLetterQueue.sendToDeadLetter(
            message, reason, attempts, metadata);

        assertNotNull(future);
        assertTrue(future.succeeded()); // Should complete successfully

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
            Future<Void> future = deadLetterQueue.sendToDeadLetter(
                message, "Reason " + i, i, new HashMap<>());
            assertTrue(future.succeeded());
        }

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(5, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
    }

    @Test
    void testSendToDeadLetter_WithNullMetadata() throws Exception {
        Message<String> message = createTestMessage("test-id-null", "test payload");
        
        Future<Void> future = deadLetterQueue.sendToDeadLetter(
            message, "Test reason", 1, null);

        assertNotNull(future);
        assertTrue(future.succeeded());

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    void testSendToDeadLetter_IntentionalTestFailure() throws Exception {
        Message<String> message = createTestMessage("test-id-intentional", "test payload");
        String reason = "INTENTIONAL TEST FAILURE - this should be logged differently";
        
        Future<Void> future = deadLetterQueue.sendToDeadLetter(
            message, reason, 3, new HashMap<>());

        assertNotNull(future);
        assertTrue(future.succeeded());

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
        Future<Void> closeResult = deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>());
        assertTrue(closeResult.succeeded());

        assertDoesNotThrow(() -> deadLetterQueue.close());
    }

    @Test
    void testMetrics_SuccessRate() throws Exception {
        // Send 3 successful messages
        for (int i = 0; i < 3; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload");
            Future<Void> rateResult = deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>());
            assertTrue(rateResult.succeeded());
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
        Future<Void> getterResult = deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>());
        assertTrue(getterResult.succeeded());

        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();

        assertEquals(TEST_TOPIC, metrics.getTopic());
        assertEquals(1, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNotNull(metrics.getLastSentTime());
        assertNull(metrics.getLastFailureTime());
        assertEquals(1.0, metrics.getSuccessRate(), 0.001);
    }

    @Test
    void testSummarizePayloadForLog_DoesNotLeakRawPayload() {
        String secretPayload = "secret-token-12345";
        String summary = LoggingDeadLetterQueue.summarizePayloadForLog(secretPayload);

        assertTrue(summary.contains("type=String"));
        assertTrue(summary.contains("length="));
        assertFalse(summary.contains(secretPayload));
    }

    @Test
    void testSummarizeHeadersForLog_DoesNotLeakHeaderValues() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer super-secret");
        headers.put("X-Api-Key", "very-secret-key");

        String summary = LoggingDeadLetterQueue.summarizeHeadersForLog(headers);
        assertEquals("count=2", summary);
        assertFalse(summary.contains("super-secret"));
        assertFalse(summary.contains("very-secret-key"));
    }

    @Test
    void testSummarizeMetadataForLog_DoesNotLeakMetadataValues() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("exceptionMessage", "password=super-secret");
        metadata.put("filterId", "orders-filter");

        String summary = LoggingDeadLetterQueue.summarizeMetadataForLog(metadata);
        assertTrue(summary.contains("count=2"));
        assertTrue(summary.contains("exceptionMessage"));
        assertTrue(summary.contains("filterId"));
        assertFalse(summary.contains("super-secret"));
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

