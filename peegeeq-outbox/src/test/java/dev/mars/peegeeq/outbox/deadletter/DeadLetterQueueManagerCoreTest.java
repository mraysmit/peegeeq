package dev.mars.peegeeq.outbox.deadletter;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORE unit tests for DeadLetterQueueManager.
 */
@Tag(TestCategories.CORE)
class DeadLetterQueueManagerCoreTest {

    private DeadLetterQueueManager manager;
    private FilterErrorHandlingConfig config;
    private static final String DEFAULT_DLQ_TOPIC = "test-dlq";

    @BeforeEach
    void setUp() {
        config = FilterErrorHandlingConfig.builder()
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic(DEFAULT_DLQ_TOPIC)
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(100))
            .build();
        
        manager = new DeadLetterQueueManager(config);
    }

    @Test
    void testCreation_WithDLQEnabled() {
        assertNotNull(manager);

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertNotNull(metrics);
        assertEquals(1, metrics.getQueueCount()); // Default queue created
    }

    @Test
    void testCreation_WithDLQDisabled() {
        FilterErrorHandlingConfig disabledConfig = FilterErrorHandlingConfig.builder()
            .deadLetterQueueEnabled(false)
            .build();

        DeadLetterQueueManager disabledManager = new DeadLetterQueueManager(disabledConfig);
        assertNotNull(disabledManager);

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = disabledManager.getMetrics();
        assertEquals(0, metrics.getQueueCount()); // No queues created
    }

    @Test
    void testSendToDeadLetter_Success() throws Exception {
        Message<String> message = createTestMessage("msg-1", "test payload");
        String filterId = "test-filter";
        String reason = "Test failure";
        int attempts = 3;
        FilterErrorHandlingConfig.ErrorClassification classification = 
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT;
        Exception exception = new RuntimeException("Test exception");

        CompletableFuture<Void> future = manager.sendToDeadLetter(
            message, filterId, reason, attempts, classification, exception);

        assertNotNull(future);
        future.get(5, TimeUnit.SECONDS);

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(1, metrics.getTotalMessages());
    }

    @Test
    void testSendToDeadLetter_WhenDisabled() {
        FilterErrorHandlingConfig disabledConfig = FilterErrorHandlingConfig.builder()
            .deadLetterQueueEnabled(false)
            .build();
        
        DeadLetterQueueManager disabledManager = new DeadLetterQueueManager(disabledConfig);
        
        Message<String> message = createTestMessage("msg-1", "payload");
        CompletableFuture<Void> future = disabledManager.sendToDeadLetter(
            message, "filter", "reason", 1, 
            FilterErrorHandlingConfig.ErrorClassification.UNKNOWN,
            new RuntimeException("test"));

        assertNotNull(future);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void testSendToDeadLetter_MultipleDifferentTopics() throws Exception {
        for (int i = 0; i < 3; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload " + i);
            manager.sendToDeadLetter(
                message, "filter-" + i, "reason " + i, i,
                FilterErrorHandlingConfig.ErrorClassification.TRANSIENT,
                new RuntimeException("exception " + i))
                .get(5, TimeUnit.SECONDS);
        }

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(3, metrics.getTotalMessages());
        // All messages go to default topic for now
        assertEquals(1, metrics.getQueueCount());
    }

    @Test
    void testSendToDeadLetter_IntentionalTestFailure() throws Exception {
        Message<String> message = createTestMessage("msg-intentional", "payload");
        String reason = "INTENTIONAL TEST FAILURE - should be logged differently";
        
        CompletableFuture<Void> future = manager.sendToDeadLetter(
            message, "filter", reason, 3,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            new RuntimeException("test"));

        future.get(5, TimeUnit.SECONDS);

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(1, metrics.getTotalMessages());
    }

    @Test
    void testGetMetrics_InitialState() {
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();

        assertNotNull(metrics);
        assertEquals(1, metrics.getQueueCount()); // Default queue
        assertEquals(0, metrics.getTotalMessages());
        assertEquals(0, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNull(metrics.getLastSentTime());
        assertNull(metrics.getLastFailureTime());
    }

    @Test
    void testClose() {
        assertDoesNotThrow(() -> manager.close());
    }

    @Test
    void testClose_AfterSends() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        manager.sendToDeadLetter(
            message, "filter", "reason", 1,
            FilterErrorHandlingConfig.ErrorClassification.UNKNOWN,
            new RuntimeException("test"))
            .get(5, TimeUnit.SECONDS);

        assertDoesNotThrow(() -> manager.close());
    }

    @Test
    void testMetrics_SuccessRate() throws Exception {
        // Send 4 successful messages
        for (int i = 0; i < 4; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload");
            manager.sendToDeadLetter(
                message, "filter", "reason", 1,
                FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
                new RuntimeException("test"))
                .get(5, TimeUnit.SECONDS);
        }

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(1.0, metrics.getSuccessRate(), 0.001);
        assertNotNull(metrics.toString());
        assertTrue(metrics.toString().contains("totalMessages=4"));
    }

    @Test
    void testMetrics_AllGetters() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        manager.sendToDeadLetter(
            message, "filter", "reason", 1,
            FilterErrorHandlingConfig.ErrorClassification.TRANSIENT,
            new RuntimeException("test"))
            .get(5, TimeUnit.SECONDS);

        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();

        assertEquals(1, metrics.getQueueCount());
        assertEquals(1, metrics.getTotalMessages());
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

