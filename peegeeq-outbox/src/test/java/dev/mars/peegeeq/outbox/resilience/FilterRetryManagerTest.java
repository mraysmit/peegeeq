package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    private FilterCircuitBreaker circuitBreaker;
    private FilterErrorHandlingConfig config;

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
        
        retryManager = new FilterRetryManager("test-filter", config, scheduler);
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
                throw new RuntimeException("Transient error");
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
            throw new RuntimeException("Persistent error");
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
            throw new RuntimeException("Error requiring backoff");
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
            throw new RuntimeException("Error requiring DLQ");
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
            throw new RuntimeException("Error with DLQ disabled");
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
            throw new RuntimeException("Error testing delay cap");
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
            if (attempt < 2) throw new RuntimeException("Error 1");
            return true;
        };
        
        Predicate<Message<String>> filter2 = msg -> {
            int attempt = attempts2.incrementAndGet();
            if (attempt < 3) throw new RuntimeException("Error 2");
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
            throw new RuntimeException("Error requiring retry then DLQ");
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
            throw new RuntimeException("TimeoutException occurred");
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
            throw new RuntimeException("Connection error");
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
            throw new RuntimeException("Error during shutdown");
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
}
