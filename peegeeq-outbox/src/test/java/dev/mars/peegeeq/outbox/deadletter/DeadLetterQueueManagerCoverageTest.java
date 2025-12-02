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
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for DeadLetterQueueManager.
 * Tests management, routing, and metrics aggregation for dead letter queues.
 */
@Tag(TestCategories.CORE)
public class DeadLetterQueueManagerCoverageTest {

    private DeadLetterQueueManager manager;
    private FilterErrorHandlingConfig config;
    private static final String TEST_TOPIC = "test-dlq";

    @BeforeEach
    void setUp() {
        config = createTestConfig(true, TEST_TOPIC);
        manager = new DeadLetterQueueManager(config);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("should create manager with DLQ enabled")
    void testConstructorWithDlqEnabled() {
        assertNotNull(manager);
        
        // Verify default queue was created
        DeadLetterQueue dlq = manager.getDeadLetterQueue(TEST_TOPIC);
        assertNotNull(dlq);
        assertEquals(TEST_TOPIC, dlq.getDeadLetterTopic());
    }

    @Test
    @DisplayName("should not create default queue when DLQ disabled")
    void testConstructorWithDlqDisabled() {
        FilterErrorHandlingConfig disabledConfig = createTestConfig(false, TEST_TOPIC);
        DeadLetterQueueManager disabledManager = new DeadLetterQueueManager(disabledConfig);
        
        DeadLetterQueue dlq = disabledManager.getDeadLetterQueue(TEST_TOPIC);
        assertNull(dlq);
        
        disabledManager.close();
    }

    @Test
    @DisplayName("should send message to dead letter successfully")
    void testSendToDeadLetterSuccess() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        Exception testException = new RuntimeException("Test error");
        
        CompletableFuture<Void> result = manager.sendToDeadLetter(
            message,
            "filter-1",
            "Processing failed",
            3,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            testException
        );
        
        result.get(5, TimeUnit.SECONDS);
        
        // Verify metrics updated
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(1, metrics.getTotalMessages());
        assertEquals(1, metrics.getTotalSent());
    }

    @Test
    @DisplayName("should fail when DLQ is disabled")
    void testSendToDeadLetterWhenDisabled() {
        FilterErrorHandlingConfig disabledConfig = createTestConfig(false, TEST_TOPIC);
        DeadLetterQueueManager disabledManager = new DeadLetterQueueManager(disabledConfig);
        
        Message<String> message = createTestMessage("msg-1", "payload");
        Exception testException = new RuntimeException("Test error");
        
        CompletableFuture<Void> result = disabledManager.sendToDeadLetter(
            message,
            "filter-1",
            "Processing failed",
            3,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            testException
        );
        
        ExecutionException exception = assertThrows(ExecutionException.class, 
            () -> result.get(5, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof IllegalStateException);
        assertTrue(exception.getCause().getMessage().contains("disabled"));
        
        disabledManager.close();
    }

    @Test
    @DisplayName("should handle multiple messages")
    void testMultipleMessages() throws Exception {
        for (int i = 1; i <= 5; i++) {
            Message<String> message = createTestMessage("msg-" + i, "payload-" + i);
            Exception testException = new RuntimeException("Error " + i);
            
            manager.sendToDeadLetter(
                message,
                "filter-" + i,
                "Failure " + i,
                i,
                FilterErrorHandlingConfig.ErrorClassification.TRANSIENT,
                testException
            ).get(5, TimeUnit.SECONDS);
        }
        
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(5, metrics.getTotalMessages());
        assertEquals(5, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
    }

    @Test
    @DisplayName("should enrich metadata with error details")
    void testMetadataEnrichment() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        Exception testException = new IllegalArgumentException("Invalid argument");
        
        // The metadata enrichment happens internally, but we can verify it doesn't throw
        CompletableFuture<Void> result = manager.sendToDeadLetter(
            message,
            "test-filter",
            "Test failure",
            3,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            testException
        );
        
        assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("should handle different error classifications")
    void testDifferentErrorClassifications() throws Exception {
        FilterErrorHandlingConfig.ErrorClassification[] classifications = {
            FilterErrorHandlingConfig.ErrorClassification.TRANSIENT,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            FilterErrorHandlingConfig.ErrorClassification.UNKNOWN
        };
        
        for (FilterErrorHandlingConfig.ErrorClassification classification : classifications) {
            Message<String> message = createTestMessage("msg-" + classification, "payload");
            Exception testException = new RuntimeException("Test");
            
            CompletableFuture<Void> result = manager.sendToDeadLetter(
                message,
                "filter-1",
                "Test " + classification,
                1,
                classification,
                testException
            );
            
            assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
        }
        
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(3, metrics.getTotalMessages());
    }

    @Test
    @DisplayName("should get specific dead letter queue by topic")
    void testGetDeadLetterQueue() {
        DeadLetterQueue dlq = manager.getDeadLetterQueue(TEST_TOPIC);
        
        assertNotNull(dlq);
        assertEquals(TEST_TOPIC, dlq.getDeadLetterTopic());
    }

    @Test
    @DisplayName("should return null for non-existent queue")
    void testGetNonExistentQueue() {
        DeadLetterQueue dlq = manager.getDeadLetterQueue("non-existent-topic");
        
        assertNull(dlq);
    }

    @Test
    @DisplayName("should get all dead letter queues")
    void testGetAllDeadLetterQueues() {
        Map<String, DeadLetterQueue> queues = manager.getAllDeadLetterQueues();
        
        assertNotNull(queues);
        assertEquals(1, queues.size());
        assertTrue(queues.containsKey(TEST_TOPIC));
    }

    @Test
    @DisplayName("should return aggregated metrics")
    void testGetMetrics() throws Exception {
        // Send a message to populate metrics
        Message<String> message = createTestMessage("msg-1", "payload");
        manager.sendToDeadLetter(
            message,
            "filter-1",
            "test",
            1,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            new RuntimeException("test")
        ).get(5, TimeUnit.SECONDS);
        
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        
        assertNotNull(metrics);
        assertEquals(1, metrics.getQueueCount());
        assertEquals(1, metrics.getTotalMessages());
        assertEquals(1, metrics.getTotalSent());
        assertEquals(0, metrics.getTotalFailed());
        assertNotNull(metrics.getLastSentTime());
    }

    @Test
    @DisplayName("should calculate success rate correctly")
    void testMetricsSuccessRate() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        manager.sendToDeadLetter(
            message,
            "filter-1",
            "test",
            1,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            new RuntimeException("test")
        ).get(5, TimeUnit.SECONDS);
        
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(1.0, metrics.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("should return zero success rate when no messages")
    void testMetricsSuccessRateNoMessages() {
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        assertEquals(0.0, metrics.getSuccessRate(), 0.01);
    }

    @Test
    @DisplayName("should handle intentional test failures")
    void testIntentionalTestFailureHandling() throws Exception {
        Message<String> message = createTestMessage("msg-test", "test payload");
        Exception testException = new RuntimeException("Test exception");
        
        CompletableFuture<Void> result = manager.sendToDeadLetter(
            message,
            "test-filter",
            "INTENTIONAL TEST FAILURE - testing DLQ manager",
            1,
            FilterErrorHandlingConfig.ErrorClassification.UNKNOWN,
            testException
        );
        
        assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("should format manager metrics toString correctly")
    void testManagerMetricsToString() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        manager.sendToDeadLetter(
            message,
            "filter-1",
            "test",
            1,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            new RuntimeException("test")
        ).get(5, TimeUnit.SECONDS);
        
        DeadLetterQueueManager.DeadLetterManagerMetrics metrics = manager.getMetrics();
        String metricsString = metrics.toString();
        
        assertNotNull(metricsString);
        assertTrue(metricsString.contains("queues=1"));
        assertTrue(metricsString.contains("totalMessages=1"));
        assertTrue(metricsString.contains("sent=1"));
    }

    @Test
    @DisplayName("should close all queues without errors")
    void testClose() throws Exception {
        // Send a message to ensure queue is active
        Message<String> message = createTestMessage("msg-1", "payload");
        manager.sendToDeadLetter(
            message,
            "filter-1",
            "test",
            1,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            new RuntimeException("test")
        ).get(5, TimeUnit.SECONDS);
        
        assertDoesNotThrow(() -> manager.close());
    }

    @Test
    @DisplayName("should handle exceptions with stack traces")
    void testExceptionWithStackTrace() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        Exception testException = new RuntimeException("Test error with stack trace");
        // Ensure stack trace is populated
        testException.fillInStackTrace();
        
        CompletableFuture<Void> result = manager.sendToDeadLetter(
            message,
            "filter-1",
            "Processing failed",
            3,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            testException
        );
        
        assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("should handle exceptions with no stack trace")
    void testExceptionWithoutStackTrace() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        Exception testException = new RuntimeException("Test error") {
            @Override
            public synchronized Throwable fillInStackTrace() {
                return this; // Don't fill in stack trace
            }
        };
        
        CompletableFuture<Void> result = manager.sendToDeadLetter(
            message,
            "filter-1",
            "Processing failed",
            1,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            testException
        );
        
        assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("should track metrics across multiple operations")
    void testMetricsTracking() throws Exception {
        // Initial metrics
        DeadLetterQueueManager.DeadLetterManagerMetrics initialMetrics = manager.getMetrics();
        assertEquals(0, initialMetrics.getTotalMessages());
        
        // Send first message
        Message<String> message1 = createTestMessage("msg-1", "payload1");
        manager.sendToDeadLetter(
            message1, "filter-1", "test1", 1,
            FilterErrorHandlingConfig.ErrorClassification.PERMANENT,
            new RuntimeException("test1")
        ).get(5, TimeUnit.SECONDS);
        
        DeadLetterQueueManager.DeadLetterManagerMetrics afterFirst = manager.getMetrics();
        assertEquals(1, afterFirst.getTotalMessages());
        
        // Send second message
        Message<String> message2 = createTestMessage("msg-2", "payload2");
        manager.sendToDeadLetter(
            message2, "filter-2", "test2", 2,
            FilterErrorHandlingConfig.ErrorClassification.TRANSIENT,
            new RuntimeException("test2")
        ).get(5, TimeUnit.SECONDS);
        
        DeadLetterQueueManager.DeadLetterManagerMetrics afterSecond = manager.getMetrics();
        assertEquals(2, afterSecond.getTotalMessages());
        assertEquals(2, afterSecond.getTotalSent());
    }

    // Helper methods
    private FilterErrorHandlingConfig createTestConfig(boolean dlqEnabled, String dlqTopic) {
        return FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(100))
            .maxRetryDelay(Duration.ofSeconds(10))
            .retryBackoffMultiplier(2.0)
            .deadLetterQueueEnabled(dlqEnabled)
            .deadLetterQueueTopic(dlqTopic)
            .build();
    }

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
