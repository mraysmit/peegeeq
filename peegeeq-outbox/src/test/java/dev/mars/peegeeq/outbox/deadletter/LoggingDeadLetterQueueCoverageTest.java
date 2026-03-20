package dev.mars.peegeeq.outbox.deadletter;

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

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.vertx.core.Future;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for LoggingDeadLetterQueue.
 * Tests the logging-based implementation of dead letter queue functionality.
 */
@Tag(TestCategories.CORE)
public class LoggingDeadLetterQueueCoverageTest {

    private LoggingDeadLetterQueue deadLetterQueue;
    private static final String TEST_TOPIC = "test-dlq-topic";

    @BeforeEach
    void setUp() {
        deadLetterQueue = new LoggingDeadLetterQueue(TEST_TOPIC);
    }

    @AfterEach
    void tearDown() {
        if (deadLetterQueue != null) {
            deadLetterQueue.close();
        }
    }

    @Test
    @DisplayName("should create dead letter queue with topic")
    void testConstructor() {
        assertNotNull(deadLetterQueue, "Dead letter queue should be created");
        assertEquals(TEST_TOPIC, deadLetterQueue.getDeadLetterTopic());
    }

    @Test
    @DisplayName("should return correct topic name")
    void testGetDeadLetterTopic() {
        String topic = deadLetterQueue.getDeadLetterTopic();
        
        assertEquals(TEST_TOPIC, topic);
    }

    @Test
    @DisplayName("should send message to dead letter successfully")
    void testSendToDeadLetterSuccess() {
        // Create test message
        Message<String> message = createTestMessage("msg-1", "test payload");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("error", "processing failed");
        
        // Send to dead letter
        Future<Void> result = deadLetterQueue.sendToDeadLetter(
            message, "Test failure reason", 3, metadata);
        
        // Verify completion
        assertTrue(result.succeeded());
        
        // Verify metrics updated
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNotNull(metrics.getLastSentTime());
    }

    @Test
    @DisplayName("should handle multiple messages sent to dead letter")
    void testMultipleMessages() {
        // Send multiple messages
        for (int i = 1; i <= 3; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload-" + i);
            Map<String, String> metadata = new HashMap<>();
            
            Future<Void> result = deadLetterQueue.sendToDeadLetter(
                message, "Failure " + i, i, metadata);
            
            assertTrue(result.succeeded());
        }
        
        // Verify metrics
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(3, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertEquals(1.0, metrics.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("should handle null metadata gracefully")
    void testNullMetadata() {
        Message<String> message = createTestMessage("msg-1", "payload");
        
        Future<Void> result = deadLetterQueue.sendToDeadLetter(
            message, "Test reason", 1, null);
        
        assertTrue(result.succeeded());
        
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    @DisplayName("should handle empty metadata")
    void testEmptyMetadata() {
        Message<String> message = createTestMessage("msg-1", "payload");
        Map<String, String> metadata = new HashMap<>();
        
        Future<Void> result = deadLetterQueue.sendToDeadLetter(
            message, "Test reason", 1, metadata);
        
        assertTrue(result.succeeded());
        
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    @DisplayName("should return metrics with correct initial state")
    void testInitialMetrics() {
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        
        assertEquals(TEST_TOPIC, metrics.getTopic());
        assertEquals(0, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNull(metrics.getLastSentTime());
        assertNull(metrics.getLastFailureTime());
        assertEquals(0.0, metrics.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("should calculate success rate correctly")
    void testMetricsSuccessRate() {
        // Send one message
        Message<String> message = createTestMessage("msg-1", "payload");
        Future<Void> rateResult = deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>());
        assertTrue(rateResult.succeeded());
        
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1.0, metrics.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("should include all message details in log")
    void testMessageDetailsLogging() {
        Message<String> message = createTestMessage("msg-detailed", "important payload");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("key1", "value1");
        metadata.put("key2", "value2");
        
        // This should log all details without throwing
        Future<Void> result = deadLetterQueue.sendToDeadLetter(
            message, "Detailed failure reason", 5, metadata);
        
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("should handle intentional test failure messages specially")
    void testIntentionalTestFailureMarking() {
        Message<String> message = createTestMessage("msg-test", "test payload");
        Map<String, String> metadata = new HashMap<>();
        
        // Send with intentional test failure marker
        Future<Void> result = deadLetterQueue.sendToDeadLetter(
            message, "INTENTIONAL TEST FAILURE - testing DLQ", 1, metadata);
        
        assertTrue(result.succeeded());
        
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    @DisplayName("should close without errors")
    void testClose() {
        assertDoesNotThrow(() -> deadLetterQueue.close());
    }

    @Test
    @DisplayName("should allow operations after creation")
    void testOperationsAfterCreation() {
        // Verify queue is usable immediately after creation
        Message<String> message = createTestMessage("msg-1", "payload");
        
        Future<Void> result = deadLetterQueue.sendToDeadLetter(
            message, "reason", 1, new HashMap<>());
        
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("should return valid metrics toString")
    void testMetricsToString() {
        Message<String> message = createTestMessage("msg-1", "payload");
        Future<Void> toStringResult = deadLetterQueue.sendToDeadLetter(message, "reason", 1, new HashMap<>());
        assertTrue(toStringResult.succeeded());
        
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        String metricsString = metrics.toString();
        
        assertNotNull(metricsString);
        assertTrue(metricsString.contains(TEST_TOPIC));
        assertTrue(metricsString.contains("sent=1"));
    }

    @Test
    @DisplayName("should handle high volume of messages")
    void testHighVolumeMessages() {
        int messageCount = 10;
        
        for (int i = 0; i < messageCount; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload-" + i);
            Future<Void> volumeResult = deadLetterQueue.sendToDeadLetter(message, "reason " + i, i, new HashMap<>());
            assertTrue(volumeResult.succeeded());
        }
        
        DeadLetterQueue.DeadLetterMetrics metrics = deadLetterQueue.getMetrics();
        assertEquals(messageCount, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
    }

    // Helper method to create test messages
    private Message<String> createTestMessage(String id, String payload) {
        return new Message<>() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public String getPayload() {
                return payload;
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("test-header", "test-value");
                return headers;
            }

            @Override
            public java.time.Instant getCreatedAt() {
                return java.time.Instant.now();
            }

            public String toString() {
                return "TestMessage{id='" + id + "', payload='" + payload + "'}";
            }
        };
    }
}
