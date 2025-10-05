package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.resilience.AsyncFilterRetryManager;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the async retry mechanism for transient filter errors.
 * Validates non-blocking retry operations with exponential backoff.
 *
 * <p><strong>IMPORTANT:</strong> These tests intentionally throw exceptions to test retry and circuit breaker logic.
 * Filters must throw exceptions (not return false) to trigger retry behavior in AsyncFilterRetryManager.
 * All intentional failures are clearly marked with "🧪 INTENTIONAL TEST FAILURE" in logs.
 * The AsyncFilterRetryManager logs only error messages (not stack traces) at DEBUG level.</p>
 */
public class AsyncRetryMechanismTest {
    private static final Logger logger = LoggerFactory.getLogger(AsyncRetryMechanismTest.class);
    
    @Test
    @DisplayName("ASYNC RETRY: Transient error recovery with exponential backoff")
    void testTransientErrorRecoveryWithBackoff() throws Exception {
        logger.info("🔄 ASYNC RETRY TEST: Transient error recovery with exponential backoff");
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        // Filter that fails twice then succeeds (simulating transient error)
        // *** INTENTIONAL TEST FAILURE: This filter deliberately throws exceptions to test retry mechanism ***
        Predicate<Message<TestMessage>> transientFailingFilter = message -> {
            int attempt = attemptCount.incrementAndGet();

            if (attempt <= 2) {
                logger.info("🧪 INTENTIONAL TEST FAILURE: ASYNC RETRY transient failure attempt {} for message {} (THIS IS EXPECTED)",
                    attempt, message.getId());
                // Throwing exception is the correct way to trigger retry logic - this is not a bug
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: ASYNC RETRY TEST - Transient network timeout (THIS IS EXPECTED)");
            }

            logger.info("✅ ASYNC RETRY: Success on attempt {} for message {} (test recovery working)", attempt, message.getId());
            return true;
        };
        
        // Configure for async retry on transient errors
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .addTransientErrorPattern("network timeout")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(50))
            .retryBackoffMultiplier(2.0)
            .maxRetryDelay(Duration.ofMillis(500))
            .circuitBreakerEnabled(false) // Disable for pure retry testing
            .build();
        
        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);
        
        // Test message
        TestMessage payload = new TestMessage("async-retry-1", "Async retry test message");
        Message<TestMessage> message = new SimpleMessage<>("async-retry-1", "test-topic", payload);
        
        // Execute async retry
        long startTime = System.currentTimeMillis();
        CompletableFuture<AsyncFilterRetryManager.FilterResult> resultFuture = 
            retryManager.executeFilterWithRetry(message, transientFailingFilter, circuitBreaker);
        
        // Wait for result
        AsyncFilterRetryManager.FilterResult result = resultFuture.get(10, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        // Verify results
        logger.info("🔄 ASYNC RETRY RESULTS:");
        logger.info("   Filter attempts: {}", attemptCount.get());
        logger.info("   Result status: {}", result.getStatus());
        logger.info("   Result accepted: {}", result.isAccepted());
        logger.info("   Total attempts: {}", result.getAttempts());
        logger.info("   Total time: {} ms", endTime - startTime);
        logger.info("   Result total time: {}", result.getTotalTime());
        
        // Assertions
        assertEquals(3, attemptCount.get(), "Should have made 3 attempts");
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.ACCEPTED, result.getStatus(), 
            "Should eventually succeed");
        assertTrue(result.isAccepted(), "Message should be accepted after retries");
        assertEquals(3, result.getAttempts(), "Result should show 3 attempts");
        assertTrue(result.getTotalTime().toMillis() >= 50, "Should have some retry delay");
        
        // Verify retry metrics
        AsyncFilterRetryManager.RetryMetrics metrics = retryManager.getMetrics();
        logger.info("   Retry metrics: {}", metrics);
        
        assertEquals(3, metrics.getTotalAttempts(), "Metrics should show 3 total attempts");
        assertEquals(1, metrics.getSuccessfulRetries(), "Metrics should show 1 successful retry");
        assertTrue(metrics.getSuccessRate() > 0, "Success rate should be positive");
        
        retryManager.shutdown();
        logger.info("✅ ASYNC RETRY TRANSIENT ERROR TEST PASSED");
    }
    
    @Test
    @DisplayName("ASYNC RETRY: Permanent error immediate rejection")
    void testPermanentErrorImmediateRejection() throws Exception {
        logger.info("🔄 ASYNC RETRY TEST: Permanent error immediate rejection");
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        // Filter that always fails with permanent error
        Predicate<Message<TestMessage>> permanentFailingFilter = message -> {
            attemptCount.incrementAndGet();
            logger.debug("🧪 ASYNC RETRY: Permanent failure for message {}", message.getId());
            throw new IllegalArgumentException("🧪 ASYNC RETRY TEST: Invalid message format");
        };
        
        // Configure for immediate rejection of permanent errors
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .addPermanentErrorPattern("Invalid message format")
            .addPermanentExceptionType(IllegalArgumentException.class)
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .maxRetries(3)
            .circuitBreakerEnabled(false)
            .build();
        
        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);
        
        // Test message
        TestMessage payload = new TestMessage("async-permanent-1", "Permanent error test message");
        Message<TestMessage> message = new SimpleMessage<>("async-permanent-1", "test-topic", payload);
        
        // Execute async retry
        long startTime = System.currentTimeMillis();
        CompletableFuture<AsyncFilterRetryManager.FilterResult> resultFuture = 
            retryManager.executeFilterWithRetry(message, permanentFailingFilter, circuitBreaker);
        
        // Wait for result
        AsyncFilterRetryManager.FilterResult result = resultFuture.get(5, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        // Verify results
        logger.info("🔄 ASYNC PERMANENT ERROR RESULTS:");
        logger.info("   Filter attempts: {}", attemptCount.get());
        logger.info("   Result status: {}", result.getStatus());
        logger.info("   Result accepted: {}", result.isAccepted());
        logger.info("   Total attempts: {}", result.getAttempts());
        logger.info("   Total time: {} ms", endTime - startTime);
        logger.info("   Rejection reason: {}", result.getReason());
        
        // Assertions
        assertEquals(1, attemptCount.get(), "Should have made only 1 attempt (no retries)");
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.REJECTED, result.getStatus(), 
            "Should be rejected immediately");
        assertFalse(result.isAccepted(), "Message should be rejected");
        assertEquals(1, result.getAttempts(), "Result should show 1 attempt");
        assertTrue(endTime - startTime < 1000, "Should be fast (no retry delays)");
        assertTrue(result.getReason().contains("Invalid message format"), 
            "Reason should contain the error message");
        
        retryManager.shutdown();
        logger.info("✅ ASYNC RETRY PERMANENT ERROR TEST PASSED");
    }
    
    @Test
    @DisplayName("ASYNC RETRY: Dead letter queue integration")
    void testDeadLetterQueueIntegration() throws Exception {
        logger.info("🔄 ASYNC RETRY TEST: Dead letter queue integration");
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        // Filter that always fails with transient error (will exhaust retries)
        // *** INTENTIONAL TEST FAILURE: This filter deliberately throws exceptions to test dead letter queue functionality ***
        Predicate<Message<TestMessage>> alwaysFailingFilter = message -> {
            attemptCount.incrementAndGet();
            logger.info("🧪 INTENTIONAL TEST FAILURE: ASYNC RETRY persistent failure attempt {} for message {} (THIS IS EXPECTED - TESTING DEAD LETTER QUEUE)",
                attemptCount.get(), message.getId());
            // Throwing exception is the correct way to trigger retry logic - this is not a bug
            throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: ASYNC RETRY TEST - Persistent network timeout (THIS IS EXPECTED)");
        };
        
        // Configure for retry then dead letter
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .addTransientErrorPattern("network timeout")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .retryBackoffMultiplier(2.0)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("async-retry-dlq")
            .circuitBreakerEnabled(false)
            .build();
        
        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);
        
        // Test message
        TestMessage payload = new TestMessage("async-dlq-1", "Dead letter queue test message");
        Message<TestMessage> message = new SimpleMessage<>("async-dlq-1", "test-topic", payload);
        
        // Execute async retry
        CompletableFuture<AsyncFilterRetryManager.FilterResult> resultFuture = 
            retryManager.executeFilterWithRetry(message, alwaysFailingFilter, circuitBreaker);
        
        // Wait for result
        AsyncFilterRetryManager.FilterResult result = resultFuture.get(10, TimeUnit.SECONDS);
        
        // Verify results
        logger.info("🔄 ASYNC DEAD LETTER QUEUE RESULTS:");
        logger.info("   Filter attempts: {}", attemptCount.get());
        logger.info("   Result status: {}", result.getStatus());
        logger.info("   Result accepted: {}", result.isAccepted());
        logger.info("   Total attempts: {}", result.getAttempts());
        logger.info("   Total time: {}", result.getTotalTime());
        logger.info("   Rejection reason: {}", result.getReason());
        
        // Assertions
        assertEquals(3, attemptCount.get(), "Should have made 3 attempts (initial + 2 retries)");
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.DEAD_LETTER, result.getStatus(), 
            "Should be sent to dead letter queue");
        assertFalse(result.isAccepted(), "Message should not be accepted");
        assertEquals(3, result.getAttempts(), "Result should show 3 attempts");
        assertTrue(result.getTotalTime().toMillis() >= 10, "Should have retry delays");
        assertTrue(result.getReason().contains("network timeout"), 
            "Reason should contain the error message");
        
        // Verify retry metrics
        AsyncFilterRetryManager.RetryMetrics metrics = retryManager.getMetrics();
        logger.info("   Retry metrics: {}", metrics);
        
        assertEquals(3, metrics.getTotalAttempts(), "Metrics should show 3 total attempts");
        assertEquals(0, metrics.getSuccessfulRetries(), "Metrics should show 0 successful retries");
        assertEquals(1, metrics.getFailedRetries(), "Metrics should show 1 failed retry sequence");
        
        retryManager.shutdown();
        logger.info("✅ ASYNC RETRY DEAD LETTER QUEUE TEST PASSED");
    }
    
    @Test
    @DisplayName("ASYNC RETRY: Circuit breaker integration")
    void testCircuitBreakerIntegration() throws Exception {
        logger.info("🔄 ASYNC RETRY TEST: Circuit breaker integration");
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        
        // Filter that always fails to trigger circuit breaker
        // *** INTENTIONAL TEST FAILURE: This filter deliberately throws exceptions to test circuit breaker functionality ***
        Predicate<Message<TestMessage>> alwaysFailingFilter = message -> {
            attemptCount.incrementAndGet();
            logger.info("🧪 INTENTIONAL TEST FAILURE: ASYNC RETRY circuit breaker trigger failure (THIS IS EXPECTED)");
            // Throwing exception is the correct way to trigger circuit breaker - this is not a bug
            throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: ASYNC RETRY TEST - System overload (THIS IS EXPECTED)");
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(2)
            .circuitBreakerMinimumRequests(2)
            .circuitBreakerTimeout(Duration.ofMillis(100))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);
        
        // First message - should fail and contribute to circuit breaker
        TestMessage payload1 = new TestMessage("async-cb-1", "Circuit breaker test 1");
        Message<TestMessage> message1 = new SimpleMessage<>("async-cb-1", "test-topic", payload1);
        
        CompletableFuture<AsyncFilterRetryManager.FilterResult> result1Future = 
            retryManager.executeFilterWithRetry(message1, alwaysFailingFilter, circuitBreaker);
        AsyncFilterRetryManager.FilterResult result1 = result1Future.get(5, TimeUnit.SECONDS);
        
        // Second message - should fail and open circuit breaker
        TestMessage payload2 = new TestMessage("async-cb-2", "Circuit breaker test 2");
        Message<TestMessage> message2 = new SimpleMessage<>("async-cb-2", "test-topic", payload2);
        
        CompletableFuture<AsyncFilterRetryManager.FilterResult> result2Future = 
            retryManager.executeFilterWithRetry(message2, alwaysFailingFilter, circuitBreaker);
        AsyncFilterRetryManager.FilterResult result2 = result2Future.get(5, TimeUnit.SECONDS);
        
        // Third message - should be rejected by open circuit breaker
        TestMessage payload3 = new TestMessage("async-cb-3", "Circuit breaker test 3");
        Message<TestMessage> message3 = new SimpleMessage<>("async-cb-3", "test-topic", payload3);
        
        int attemptsBeforeCircuitBreaker = attemptCount.get();
        CompletableFuture<AsyncFilterRetryManager.FilterResult> result3Future = 
            retryManager.executeFilterWithRetry(message3, alwaysFailingFilter, circuitBreaker);
        AsyncFilterRetryManager.FilterResult result3 = result3Future.get(5, TimeUnit.SECONDS);
        int attemptsAfterCircuitBreaker = attemptCount.get();
        
        // Verify results
        logger.info("🔄 ASYNC CIRCUIT BREAKER RESULTS:");
        logger.info("   Total filter attempts: {}", attemptCount.get());
        logger.info("   Result 1: {}", result1);
        logger.info("   Result 2: {}", result2);
        logger.info("   Result 3: {}", result3);
        
        FilterCircuitBreaker.CircuitBreakerMetrics finalMetrics = circuitBreaker.getMetrics();
        logger.info("   Final circuit breaker metrics: {}", finalMetrics);
        
        // Assertions
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.REJECTED, result1.getStatus());
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.REJECTED, result2.getStatus());
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.REJECTED, result3.getStatus());
        
        // Circuit breaker should prevent the third filter call
        assertEquals(attemptsBeforeCircuitBreaker, attemptsAfterCircuitBreaker, 
            "Circuit breaker should prevent third filter call");
        assertTrue(result3.getReason().contains("Circuit breaker open"), 
            "Third result should indicate circuit breaker rejection");
        
        assertEquals(FilterCircuitBreaker.State.OPEN, finalMetrics.getState(), 
            "Circuit breaker should be open");
        
        retryManager.shutdown();
        logger.info("✅ ASYNC RETRY CIRCUIT BREAKER TEST PASSED");
    }
    
    // Test message class
    public static class TestMessage {
        private final String id;
        private final String content;
        
        public TestMessage(String id, String content) {
            this.id = id;
            this.content = content;
        }
        
        public String getId() { return id; }
        public String getContent() { return content; }
        
        @Override
        public String toString() {
            return String.format("TestMessage{id='%s', content='%s'}", id, content);
        }
    }
}
