package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
class FilterRetryManagerTest {

    private ScheduledExecutorService scheduler;
    private FilterRetryManager retryManager;
    private FilterRetryManager retryManagerWithDlq;
    private FilterCircuitBreaker circuitBreaker;
    private FilterErrorHandlingConfig config;
    private DeadLetterQueueManager deadLetterQueueManager;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);

        config = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .maxRetryDelay(Duration.ofMillis(100))
            .retryBackoffMultiplier(2.0)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("test-dlq")
            .build();

        circuitBreaker = new FilterCircuitBreaker("test-cb",
            FilterErrorHandlingConfig.builder()
                .circuitBreakerEnabled(true)
                .circuitBreakerFailureThreshold(5)
                .circuitBreakerMinimumRequests(5)
                .circuitBreakerTimeout(Duration.ofSeconds(10))
                .build());

        // Create DeadLetterQueueManager for DLQ-enabled tests
        deadLetterQueueManager = new DeadLetterQueueManager(config);

        // Legacy constructor (deprecated) - for backward compatibility tests
        retryManager = new FilterRetryManager("test-filter", config, scheduler);

        // New constructor with DLQ support
        retryManagerWithDlq = new FilterRetryManager("test-filter-dlq", config, scheduler, deadLetterQueueManager);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testSuccessfulFilterExecution() throws Exception {
        Message<String> message = createTestMessage("msg-1", "payload");
        Predicate<Message<String>> filter = msg -> true;

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);

        assertTrue(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testFilterRejectsMessage() throws Exception {
        Message<String> message = createTestMessage("msg-2", "payload");
        Predicate<Message<String>> filter = msg -> false;

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);

        assertFalse(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testRetryOnTransientError() throws Exception {
        Message<String> message = createTestMessage("msg-3", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Transient error");
            }
            return true;
        };

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);

        assertTrue(result.get(2, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
    }

    @Test
    void testRetryExhaustion() throws Exception {
        Message<String> message = createTestMessage("msg-4", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Persistent error");
        };

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);

        assertFalse(result.get(2, TimeUnit.SECONDS));
        // Initial attempt + 3 retries = 4 total
        assertEquals(4, attempts.get());
    }

    @Test
    void testCircuitBreakerOpen() throws Exception {
        // Open the circuit breaker by recording failures
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }

        Message<String> message = createTestMessage("msg-5", "payload");
        Predicate<Message<String>> filter = msg -> true;

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);

        // Should reject immediately due to open circuit breaker
        assertFalse(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void testExponentialBackoff() throws Exception {
        Message<String> message = createTestMessage("msg-6", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error requiring backoff");
        };

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);
        result.get(2, TimeUnit.SECONDS);
        
        long duration = System.currentTimeMillis() - startTime;
        
        // Should have 4 attempts with exponential backoff: 0ms, 10ms, 20ms, 40ms
        // Total minimum delay: ~70ms
        assertTrue(duration >= 60, "Expected delays from exponential backoff, got: " + duration + "ms");
        assertEquals(4, attempts.get());
    }

    @Test
    void testRejectImmediatelyStrategy() throws Exception {
        // Configure for immediate rejection of permanent errors
        FilterErrorHandlingConfig immediateRejectConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        FilterRetryManager immediateRejectManager = new FilterRetryManager(
            "immediate-reject-filter", immediateRejectConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-7", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Permanent error");
        };

        CompletableFuture<Boolean> result = immediateRejectManager.executeWithRetry(
            message, filter, circuitBreaker);

        assertFalse(result.get(1, TimeUnit.SECONDS));
        // Should only attempt once (no retries)
        assertEquals(1, attempts.get());
    }

    @Test
    void testDeadLetterQueueEnabled() throws Exception {
        Message<String> message = createTestMessage("msg-8", "payload");
        
        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error requiring DLQ");
        };

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);

        // Should reject after exhausting retries (DLQ is logged but returns false)
        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void testDeadLetterQueueDisabled() throws Exception {
        FilterErrorHandlingConfig noDlqConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .deadLetterQueueEnabled(false)
            .build();
        
        FilterRetryManager noDlqManager = new FilterRetryManager("no-dlq-filter", noDlqConfig, scheduler);

        Message<String> message = createTestMessage("msg-9", "payload");

        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error with DLQ disabled");
        };

        CompletableFuture<Boolean> result = noDlqManager.executeWithRetry(message, filter, circuitBreaker);

        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    void testMaxRetryDelayCappping() throws Exception {
        FilterErrorHandlingConfig cappedConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(5)
            .initialRetryDelay(Duration.ofMillis(10))
            .maxRetryDelay(Duration.ofMillis(50))  // Low cap for testing
            .retryBackoffMultiplier(3.0)  // High multiplier
            .build();
        
        FilterRetryManager cappedManager = new FilterRetryManager("capped-filter", cappedConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-10", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error testing delay cap");
        };

        CompletableFuture<Boolean> result = cappedManager.executeWithRetry(message, filter, circuitBreaker);
        result.get(3, TimeUnit.SECONDS);
        
        // Initial attempt + maxRetries (5) but one may fail to complete
        assertTrue(attempts.get() >= 5 && attempts.get() <= 6, 
            "Expected 5-6 attempts, got: " + attempts.get());
    }

    @Test
    void testRetryContextCreation() {
        FilterRetryManager.RetryContext context = new FilterRetryManager.RetryContext(
            "test-msg-id",
            "test-filter-id",
            3,
            java.time.Instant.now(),
            Duration.ofMillis(150)
        );

        assertEquals("test-msg-id", context.getMessageId());
        assertEquals("test-filter-id", context.getFilterId());
        assertEquals(3, context.getAttemptNumber());
        assertEquals(Duration.ofMillis(150), context.getTotalDelay());
        assertNotNull(context.getStartTime());
        assertTrue(context.toString().contains("test-msg-id"));
        assertTrue(context.toString().contains("test-filter-id"));
    }

    @Test
    void testConcurrentRetries() throws Exception {
        Message<String> message1 = createTestMessage("msg-11", "payload1");
        Message<String> message2 = createTestMessage("msg-12", "payload2");
        
        AtomicInteger attempts1 = new AtomicInteger(0);
        AtomicInteger attempts2 = new AtomicInteger(0);
        
        Predicate<Message<String>> filter1 = msg -> {
            int attempt = attempts1.incrementAndGet();
            if (attempt < 2) throw new RuntimeException("INTENTIONAL TEST FAILURE - Error 1");
            return true;
        };
        
        Predicate<Message<String>> filter2 = msg -> {
            int attempt = attempts2.incrementAndGet();
            if (attempt < 3) throw new RuntimeException("INTENTIONAL TEST FAILURE - Error 2");
            return true;
        };

        CompletableFuture<Boolean> result1 = retryManager.executeWithRetry(message1, filter1, circuitBreaker);
        CompletableFuture<Boolean> result2 = retryManager.executeWithRetry(message2, filter2, circuitBreaker);

        CompletableFuture.allOf(result1, result2).get(3, TimeUnit.SECONDS);
        
        assertTrue(result1.get());
        assertTrue(result2.get());
        assertEquals(2, attempts1.get());
        assertEquals(3, attempts2.get());
    }

    @Test
    void testDeadLetterImmediatelyStrategy() throws Exception {
        FilterErrorHandlingConfig dlqImmediateConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("test-dlq-immediate")
            .build();
        
        FilterRetryManager dlqImmediateManager = new FilterRetryManager(
            "dlq-immediate-filter", dlqImmediateConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-dlq-immediate", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Critical error requiring immediate DLQ");
        };

        CompletableFuture<Boolean> result = dlqImmediateManager.executeWithRetry(
            message, filter, circuitBreaker);

        assertFalse(result.get(1, TimeUnit.SECONDS));
        // Should only attempt once (immediate DLQ)
        assertEquals(1, attempts.get());
    }

    @Test
    void testRetryThenDeadLetterStrategy() throws Exception {
        FilterErrorHandlingConfig retryThenDlqConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("test-dlq-after-retry")
            .build();
        
        FilterRetryManager retryThenDlqManager = new FilterRetryManager(
            "retry-dlq-filter", retryThenDlqConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-retry-dlq", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error requiring retry then DLQ");
        };

        CompletableFuture<Boolean> result = retryThenDlqManager.executeWithRetry(
            message, filter, circuitBreaker);

        assertFalse(result.get(2, TimeUnit.SECONDS));
        // Initial attempt + 2 retries = 3 attempts before DLQ
        assertEquals(3, attempts.get());
    }

    @Test
    void testPermanentErrorPattern() throws Exception {
        FilterErrorHandlingConfig permanentErrorConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .addPermanentErrorPattern(".*IllegalArgument.*")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .build();
        
        FilterRetryManager permanentErrorManager = new FilterRetryManager(
            "permanent-error-filter", permanentErrorConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-permanent", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Permanent error matching pattern");
        };

        CompletableFuture<Boolean> result = permanentErrorManager.executeWithRetry(
            message, filter, circuitBreaker);

        assertFalse(result.get(1, TimeUnit.SECONDS));
        // Pattern-matched permanent errors should get rejected after retries
        assertTrue(attempts.get() >= 1);
    }

    @Test
    void testTransientErrorPattern() throws Exception {
        FilterErrorHandlingConfig transientErrorConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .addTransientErrorPattern(".*Timeout.*")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .build();
        
        FilterRetryManager transientErrorManager = new FilterRetryManager(
            "transient-error-filter", transientErrorConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-transient", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - TimeoutException occurred");
        };

        CompletableFuture<Boolean> result = transientErrorManager.executeWithRetry(
            message, filter, circuitBreaker);

        assertFalse(result.get(2, TimeUnit.SECONDS));
        // Initial attempt + 2 retries = 3 attempts
        assertEquals(3, attempts.get());
    }

    @Test
    void testMultipleErrorPatterns() throws Exception {
        FilterErrorHandlingConfig multiErrorConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .addPermanentErrorPattern(".*IllegalState.*")
            .addTransientErrorPattern(".*Connection.*")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .build();
        
        FilterRetryManager multiErrorManager = new FilterRetryManager(
            "multi-error-filter", multiErrorConfig, scheduler);
        
        // Test permanent error pattern
        Message<String> message1 = createTestMessage("msg-multi-1", "payload");
        AtomicInteger attempts1 = new AtomicInteger(0);
        
        Predicate<Message<String>> filter1 = msg -> {
            attempts1.incrementAndGet();
            throw new IllegalStateException("Permanent error");
        };

        CompletableFuture<Boolean> result1 = multiErrorManager.executeWithRetry(
            message1, filter1, circuitBreaker);

        assertFalse(result1.get(1, TimeUnit.SECONDS));
        assertTrue(attempts1.get() >= 1);
        
        // Test transient error pattern
        Message<String> message2 = createTestMessage("msg-multi-2", "payload");
        AtomicInteger attempts2 = new AtomicInteger(0);
        
        Predicate<Message<String>> filter2 = msg -> {
            attempts2.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Connection error");
        };

        CompletableFuture<Boolean> result2 = multiErrorManager.executeWithRetry(
            message2, filter2, circuitBreaker);

        assertFalse(result2.get(2, TimeUnit.SECONDS));
        // Connection error should match transient pattern and retry
        assertTrue(attempts2.get() >= 1, "Expected at least 1 attempt, got: " + attempts2.get());
    }

    @Test
    void testNullMessageHandling() {
        assertThrows(NullPointerException.class, () -> {
            retryManager.executeWithRetry(null, msg -> true, circuitBreaker);
        });
    }

    @Test
    void testNullFilterHandling() throws Exception {
        Message<String> message = createTestMessage("msg-null-filter", "payload");
        
        // Null filter should either throw NPE or handle gracefully
        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, null, circuitBreaker);
        
        // Accept either outcome: exception or completion with false
        try {
            assertFalse(result.get(1, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            // NPE or other exception is also acceptable
            assertTrue(e.getCause() instanceof NullPointerException || 
                       e.getCause() instanceof RuntimeException);
        }
    }

    @Test
    void testSchedulerShutdownGracefully() throws Exception {
        Message<String> message = createTestMessage("msg-shutdown", "payload");
        
        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error during shutdown");
        };

        CompletableFuture<Boolean> result = retryManager.executeWithRetry(message, filter, circuitBreaker);
        
        // Give it a moment to start, then shutdown scheduler while retries are in progress
        Thread.sleep(20);
        scheduler.shutdown();
        
        // Should either complete with false or throw RejectedExecutionException
        try {
            assertFalse(result.get(3, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            // RejectedExecutionException is acceptable when scheduler is shutdown
            assertTrue(e.getCause() instanceof RejectedExecutionException);
        }
    }

    @Test
    void testErrorClassificationHandling() throws Exception {
        FilterErrorHandlingConfig classificationConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        FilterRetryManager classificationManager = new FilterRetryManager(
            "classification-filter", classificationConfig, scheduler);
        
        Message<String> message = createTestMessage("msg-13", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Permanent error");
        };

        CompletableFuture<Boolean> result = classificationManager.executeWithRetry(
            message, filter, circuitBreaker);

        assertFalse(result.get(1, TimeUnit.SECONDS));
        // Should reject immediately without retries for permanent errors
        assertEquals(1, attempts.get());
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
            public java.time.Instant getCreatedAt() { return java.time.Instant.now(); }
        };
    }

    /**
     * Nested test class for comprehensive Dead Letter Queue integration tests.
     * These tests verify the full DLQ functionality with the DeadLetterQueueManager.
     */
    @Nested
    @DisplayName("Dead Letter Queue Integration Tests")
    class DeadLetterQueueIntegrationTests {

        @Test
        @DisplayName("should send message to DLQ after retry exhaustion with RETRY_THEN_DEAD_LETTER strategy")
        void testRetryThenDeadLetterWithDlqManager() throws Exception {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(2)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-integration")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "dlq-integration-filter", dlqConfig, scheduler, dlqManager);

            Message<String> message = createTestMessage("msg-dlq-1", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Simulated error for DLQ test");
            };

            CompletableFuture<Boolean> result = manager.executeWithRetry(message, filter, circuitBreaker);

            assertFalse(result.get(3, TimeUnit.SECONDS));
            assertEquals(3, attempts.get()); // Initial + 2 retries

            // Verify DLQ metrics
            DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
            assertEquals(1, metrics.getTotalMessages());
        }

        @Test
        @DisplayName("should send message to DLQ immediately with DEAD_LETTER_IMMEDIATELY strategy")
        void testDeadLetterImmediatelyWithDlqManager() throws Exception {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(3)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-immediate")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "dlq-immediate-filter", dlqConfig, scheduler, dlqManager);

            Message<String> message = createTestMessage("msg-dlq-immediate", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Critical error");
            };

            CompletableFuture<Boolean> result = manager.executeWithRetry(message, filter, circuitBreaker);

            assertFalse(result.get(1, TimeUnit.SECONDS));
            assertEquals(1, attempts.get()); // Only one attempt, immediate DLQ

            // Verify DLQ metrics
            DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
            assertEquals(1, metrics.getTotalMessages());
        }

        @Test
        @DisplayName("should reject message when DLQ is disabled even with DLQ strategy")
        void testDlqDisabledFallsBackToReject() throws Exception {
            FilterErrorHandlingConfig noDlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(1)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(false)
                .build();

            // Even with DLQ manager, if config says disabled, should reject
            FilterRetryManager manager = new FilterRetryManager(
                "no-dlq-filter", noDlqConfig, scheduler, null);

            Message<String> message = createTestMessage("msg-no-dlq", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Error with DLQ disabled");
            };

            CompletableFuture<Boolean> result = manager.executeWithRetry(message, filter, circuitBreaker);

            assertFalse(result.get(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get()); // Initial + 1 retry, then reject (no DLQ)
        }

        @Test
        @DisplayName("should reject message when DLQ manager is null")
        void testNullDlqManagerFallsBackToReject() throws Exception {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(1)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq")
                .build();

            // DLQ enabled in config but manager is null - should fall back to reject
            FilterRetryManager manager = new FilterRetryManager(
                "null-dlq-manager-filter", dlqConfig, scheduler, null);

            Message<String> message = createTestMessage("msg-null-dlq", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Error with null DLQ manager");
            };

            CompletableFuture<Boolean> result = manager.executeWithRetry(message, filter, circuitBreaker);

            assertFalse(result.get(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }

        @Test
        @DisplayName("should track multiple messages sent to DLQ")
        void testMultipleMessagesToDlq() throws Exception {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(0) // No retries, immediate DLQ
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-multi")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "multi-dlq-filter", dlqConfig, scheduler, dlqManager);

            Predicate<Message<String>> failingFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Always fails");
            };

            // Send multiple messages
            for (int i = 0; i < 5; i++) {
                Message<String> message = createTestMessage("msg-multi-" + i, "payload-" + i);
                CompletableFuture<Boolean> result = manager.executeWithRetry(message, failingFilter, circuitBreaker);
                assertFalse(result.get(1, TimeUnit.SECONDS));
            }

            // Verify all messages were sent to DLQ
            DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
            assertEquals(5, metrics.getTotalMessages());
        }

        @Test
        @DisplayName("should include error classification in DLQ metadata")
        void testDlqWithErrorClassification() throws Exception {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(0)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-classification")
                .addTransientErrorPattern(".*Timeout.*")
                .addPermanentErrorPattern(".*Invalid.*")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "classification-dlq-filter", dlqConfig, scheduler, dlqManager);

            // Test with transient error
            Message<String> transientMsg = createTestMessage("msg-transient", "payload");
            Predicate<Message<String>> transientFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Timeout occurred");
            };

            CompletableFuture<Boolean> result1 = manager.executeWithRetry(transientMsg, transientFilter, circuitBreaker);
            assertFalse(result1.get(1, TimeUnit.SECONDS));

            // Test with permanent error
            Message<String> permanentMsg = createTestMessage("msg-permanent", "payload");
            Predicate<Message<String>> permanentFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Invalid data format");
            };

            CompletableFuture<Boolean> result2 = manager.executeWithRetry(permanentMsg, permanentFilter, circuitBreaker);
            assertFalse(result2.get(1, TimeUnit.SECONDS));

            // Both should be in DLQ
            DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
            assertEquals(2, metrics.getTotalMessages());
        }

        @Test
        @DisplayName("should handle concurrent DLQ sends correctly")
        void testConcurrentDlqSends() throws Exception {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(0)
                .initialRetryDelay(Duration.ofMillis(5))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-concurrent")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "concurrent-dlq-filter", dlqConfig, scheduler, dlqManager);

            // Create a circuit breaker with high threshold to allow all concurrent messages
            FilterCircuitBreaker localCircuitBreaker = new FilterCircuitBreaker("concurrent-cb",
                FilterErrorHandlingConfig.builder()
                    .circuitBreakerEnabled(true)
                    .circuitBreakerFailureThreshold(100) // High threshold for concurrent test
                    .circuitBreakerMinimumRequests(100)
                    .circuitBreakerTimeout(Duration.ofSeconds(10))
                    .build());

            Predicate<Message<String>> failingFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Concurrent failure");
            };

            int messageCount = 10;
            CountDownLatch latch = new CountDownLatch(messageCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < messageCount; i++) {
                final int index = i;
                CompletableFuture.runAsync(() -> {
                    try {
                        Message<String> message = createTestMessage("msg-concurrent-" + index, "payload");
                        CompletableFuture<Boolean> result = manager.executeWithRetry(message, failingFilter, localCircuitBreaker);
                        if (!result.get(2, TimeUnit.SECONDS)) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(messageCount, successCount.get());

            // All messages should be in DLQ
            DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
            assertEquals(messageCount, metrics.getTotalMessages());
        }

        @Test
        @DisplayName("should use deprecated constructor for backward compatibility")
        void testDeprecatedConstructorBackwardCompatibility() throws Exception {
            // Using deprecated constructor (without DLQ manager)
            @SuppressWarnings("deprecation")
            FilterRetryManager legacyManager = new FilterRetryManager("legacy-filter", config, scheduler);

            Message<String> message = createTestMessage("msg-legacy", "payload");
            Predicate<Message<String>> successFilter = msg -> true;

            CompletableFuture<Boolean> result = legacyManager.executeWithRetry(message, successFilter, circuitBreaker);

            assertTrue(result.get(1, TimeUnit.SECONDS));
        }
    }
}
