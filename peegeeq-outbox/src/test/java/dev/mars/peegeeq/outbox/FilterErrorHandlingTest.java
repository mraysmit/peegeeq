package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the configurable filter error handling system.
 * Demonstrates different strategies for handling filter failures.
 *
 * <p><strong>IMPORTANT:</strong> These tests intentionally throw exceptions to test circuit breaker and error handling logic.
 * Filters must throw exceptions (not return false) to trigger error handling in AsyncFilterRetryManager.
 * All intentional failures are clearly marked with "🧪 INTENTIONAL TEST FAILURE" in logs.
 * The system logs only error messages (not stack traces) at appropriate levels.</p>
 */
@Tag(TestCategories.CORE)
public class FilterErrorHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(FilterErrorHandlingTest.class);

    @Test
    @DisplayName("Test circuit breaker opens after repeated filter failures")
    void testCircuitBreakerOpensAfterFailures() {
        logger.info("===== TESTING CIRCUIT BREAKER BEHAVIOR =====");
        logger.info("*** INTENTIONAL TEST FAILURES: This test deliberately generates exceptions to test circuit breaker ***");

        // Configure circuit breaker with low thresholds for testing
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(3)
            .circuitBreakerMinimumRequests(3)
            .circuitBreakerTimeout(Duration.ofSeconds(1))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();

        AtomicInteger filterCallCount = new AtomicInteger(0);

        // Create a filter that always fails
        // *** INTENTIONAL TEST FAILURE: This filter deliberately throws exceptions to test circuit breaker ***
        Predicate<Message<TestMessage>> alwaysFailingFilter = message -> {
            int callNumber = filterCallCount.incrementAndGet();
            logger.info("Filter call #{} - Throwing exception (THIS IS EXPECTED)", callNumber);
            // Throwing exception is the correct way to trigger circuit breaker - this is not a bug
            throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Filter always fails (call #" + callNumber + ") - THIS IS EXPECTED");
        };

        // Create consumer with the failing filter and custom config
        // We'll create a mock consumer group for this test
        OutboxConsumerGroup<TestMessage> mockConsumerGroup = null; // We don't need it for this test

        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "failing-filter-consumer",
            "test-group",
            "test-topic",
            message -> Future.succeededFuture(),
            alwaysFailingFilter,
            mockConsumerGroup,
            config
        );

        member.start();

        // Test messages
        TestMessage testMessage = new TestMessage("test-1", "Test message");
        Message<TestMessage> message = createMessage("msg-1", testMessage);

        // First few calls should invoke the filter (and fail)
        logger.info("Testing initial filter failures...");
        assertFalse(member.acceptsMessage(message), "Message should be rejected due to filter failure");
        assertFalse(member.acceptsMessage(message), "Message should be rejected due to filter failure");
        assertFalse(member.acceptsMessage(message), "Message should be rejected due to filter failure");

        // Get circuit breaker metrics
        FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
        logger.info("Circuit breaker metrics: {}", metrics);

        // Circuit breaker should be open now
        assertEquals(FilterCircuitBreaker.State.OPEN, metrics.getState(),
            "Circuit breaker should be OPEN after repeated failures");

        // Additional calls should be rejected without invoking the filter
        logger.info("Testing circuit breaker fast-fail...");
        int callCountBeforeFastFail = filterCallCount.get();
        assertFalse(member.acceptsMessage(message), "Message should be rejected by open circuit breaker");
        assertEquals(callCountBeforeFastFail, filterCallCount.get(),
            "Filter should not be called when circuit breaker is open");

        logger.info("Circuit breaker successfully prevented additional filter calls");

        member.close();
        logger.info("===== CIRCUIT BREAKER TEST COMPLETED =====");
    }
    
    @Test
    @DisplayName("Test error classification for transient vs permanent errors")
    void testErrorClassification() {
        logger.info("===== TESTING ERROR CLASSIFICATION =====");
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .addTransientErrorPattern("timeout")
            .addTransientErrorPattern("connection")
            .addPermanentErrorPattern("invalid")
            .addPermanentErrorPattern("malformed")
            .build();
        
        // Test transient error classification
        Exception timeoutError = new RuntimeException("Connection timeout occurred");
        FilterErrorHandlingConfig.ErrorClassification classification = config.classifyError(timeoutError);
        assertEquals(FilterErrorHandlingConfig.ErrorClassification.TRANSIENT, classification,
            "Timeout errors should be classified as transient");
        logger.info("Timeout error correctly classified as TRANSIENT");
        
        // Test permanent error classification
        Exception invalidError = new IllegalArgumentException("Invalid message format");
        classification = config.classifyError(invalidError);
        assertEquals(FilterErrorHandlingConfig.ErrorClassification.PERMANENT, classification,
            "Invalid format errors should be classified as permanent");
        logger.info("Invalid format error correctly classified as PERMANENT");
        
        // Test unknown error classification
        Exception unknownError = new RuntimeException("Some random error");
        classification = config.classifyError(unknownError);
        assertEquals(FilterErrorHandlingConfig.ErrorClassification.UNKNOWN, classification,
            "Unknown errors should be classified as unknown");
        logger.info("Unknown error correctly classified as UNKNOWN");
        
        logger.info("===== ERROR CLASSIFICATION TEST COMPLETED =====");
    }
    
    @Test
    @DisplayName("Test different filter error strategies")
    void testFilterErrorStrategies() {
        logger.info("===== TESTING FILTER ERROR STRATEGIES =====");
        
        // Test REJECT_IMMEDIATELY strategy
        FilterErrorHandlingConfig rejectConfig = FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .circuitBreakerEnabled(false) // Disable for this test
            .build();
        
        OutboxConsumerGroupMember<TestMessage> rejectMember = new OutboxConsumerGroupMember<>(
            "reject-consumer",
            "test-group",
            "test-topic",
            message -> Future.succeededFuture(),
            message -> {
                // Throwing exception is the correct way to test REJECT_IMMEDIATELY strategy
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Reject immediately - THIS IS EXPECTED");
            },
            null, // Mock consumer group not needed for this test
            rejectConfig
        );
        
        rejectMember.start();
        
        TestMessage testMessage = new TestMessage("test-1", "Test message");
        Message<TestMessage> message = createMessage("msg-1", testMessage);
        
        logger.info("Testing REJECT_IMMEDIATELY strategy...");
        assertFalse(rejectMember.acceptsMessage(message), 
            "Message should be rejected immediately with REJECT_IMMEDIATELY strategy");
        logger.info("REJECT_IMMEDIATELY strategy working correctly");
        
        rejectMember.close();
        
        logger.info("===== FILTER ERROR STRATEGIES TEST COMPLETED =====");
    }
    
    @Test
    @DisplayName("Test testing configuration for intentional failures")
    void testTestingConfiguration() {
        logger.info("===== TESTING CONFIGURATION FOR TESTS =====");
        
        // Use testing configuration that handles intentional test failures gracefully
        FilterErrorHandlingConfig testConfig = FilterErrorHandlingConfig.testingConfig();
        
        OutboxConsumerGroupMember<TestMessage> testMember = new OutboxConsumerGroupMember<>(
            "test-consumer",
            "test-group",
            "test-topic",
            message -> Future.succeededFuture(),
            message -> {
                // Throwing exception is the correct way to test error handling with testing config
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Simulated test error - THIS IS EXPECTED");
            },
            null, // Mock consumer group not needed for this test
            testConfig
        );
        
        testMember.start();
        
        TestMessage testMessage = new TestMessage("test-1", "Test message");
        Message<TestMessage> message = createMessage("msg-1", testMessage);
        
        logger.info("Testing configuration handles intentional test failures...");
        assertFalse(testMember.acceptsMessage(message), 
            "Message should be rejected but handled gracefully");
        logger.info("Testing configuration handles intentional failures without stack traces");
        
        testMember.close();
        
        logger.info("===== TESTING CONFIGURATION TEST COMPLETED =====");
    }
    
    // Helper methods
    private Message<TestMessage> createMessage(String id, TestMessage payload) {
        return new Message<TestMessage>() {
            @Override
            public String getId() { return id; }

            @Override
            public TestMessage getPayload() { return payload; }

            @Override
            public java.time.Instant getCreatedAt() { return java.time.Instant.now(); }

            @Override
            public Map<String, String> getHeaders() { return new HashMap<>(); }
        };
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
