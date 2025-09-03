package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the configurable filter error handling system.
 * Demonstrates different strategies for handling filter failures.
 */
public class FilterErrorHandlingTest {

    @Test
    @DisplayName("Test circuit breaker opens after repeated filter failures")
    void testCircuitBreakerOpensAfterFailures() {
        System.out.println("\n🔥 ===== TESTING CIRCUIT BREAKER BEHAVIOR ===== 🔥");

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
        Predicate<Message<TestMessage>> alwaysFailingFilter = message -> {
            int callNumber = filterCallCount.incrementAndGet();
            System.out.println("🧪 Filter call #" + callNumber + " - About to throw exception");
            throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Filter always fails (call #" + callNumber + ")");
        };

        // Create consumer with the failing filter and custom config
        // We'll create a mock consumer group for this test
        OutboxConsumerGroup<TestMessage> mockConsumerGroup = null; // We don't need it for this test

        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "failing-filter-consumer",
            "test-group",
            "test-topic",
            message -> CompletableFuture.completedFuture(null),
            alwaysFailingFilter,
            mockConsumerGroup,
            config
        );

        member.start();

        // Test messages
        TestMessage testMessage = new TestMessage("test-1", "Test message");
        Message<TestMessage> message = createMessage("msg-1", testMessage);

        // First few calls should invoke the filter (and fail)
        System.out.println("\n📋 Testing initial filter failures...");
        assertFalse(member.acceptsMessage(message), "Message should be rejected due to filter failure");
        assertFalse(member.acceptsMessage(message), "Message should be rejected due to filter failure");
        assertFalse(member.acceptsMessage(message), "Message should be rejected due to filter failure");

        // Get circuit breaker metrics
        FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
        System.out.println("📊 Circuit breaker metrics: " + metrics);

        // Circuit breaker should be open now
        assertEquals(FilterCircuitBreaker.State.OPEN, metrics.getState(),
            "Circuit breaker should be OPEN after repeated failures");

        // Additional calls should be rejected without invoking the filter
        System.out.println("\n⚡ Testing circuit breaker fast-fail...");
        int callCountBeforeFastFail = filterCallCount.get();
        assertFalse(member.acceptsMessage(message), "Message should be rejected by open circuit breaker");
        assertEquals(callCountBeforeFastFail, filterCallCount.get(),
            "Filter should not be called when circuit breaker is open");

        System.out.println("✅ Circuit breaker successfully prevented additional filter calls");

        member.close();
        System.out.println("🔥 ===== CIRCUIT BREAKER TEST COMPLETED ===== 🔥\n");
    }
    
    @Test
    @DisplayName("Test error classification for transient vs permanent errors")
    void testErrorClassification() {
        System.out.println("\n🔍 ===== TESTING ERROR CLASSIFICATION ===== 🔍");
        
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
        System.out.println("✅ Timeout error correctly classified as TRANSIENT");
        
        // Test permanent error classification
        Exception invalidError = new IllegalArgumentException("Invalid message format");
        classification = config.classifyError(invalidError);
        assertEquals(FilterErrorHandlingConfig.ErrorClassification.PERMANENT, classification,
            "Invalid format errors should be classified as permanent");
        System.out.println("✅ Invalid format error correctly classified as PERMANENT");
        
        // Test unknown error classification
        Exception unknownError = new RuntimeException("Some random error");
        classification = config.classifyError(unknownError);
        assertEquals(FilterErrorHandlingConfig.ErrorClassification.UNKNOWN, classification,
            "Unknown errors should be classified as unknown");
        System.out.println("✅ Unknown error correctly classified as UNKNOWN");
        
        System.out.println("🔍 ===== ERROR CLASSIFICATION TEST COMPLETED ===== 🔍\n");
    }
    
    @Test
    @DisplayName("Test different filter error strategies")
    void testFilterErrorStrategies() {
        System.out.println("\n⚙️ ===== TESTING FILTER ERROR STRATEGIES ===== ⚙️");
        
        // Test REJECT_IMMEDIATELY strategy
        FilterErrorHandlingConfig rejectConfig = FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .circuitBreakerEnabled(false) // Disable for this test
            .build();
        
        OutboxConsumerGroupMember<TestMessage> rejectMember = new OutboxConsumerGroupMember<>(
            "reject-consumer",
            "test-group",
            "test-topic",
            message -> CompletableFuture.completedFuture(null),
            message -> { throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Reject immediately"); },
            null, // Mock consumer group not needed for this test
            rejectConfig
        );
        
        rejectMember.start();
        
        TestMessage testMessage = new TestMessage("test-1", "Test message");
        Message<TestMessage> message = createMessage("msg-1", testMessage);
        
        System.out.println("📋 Testing REJECT_IMMEDIATELY strategy...");
        assertFalse(rejectMember.acceptsMessage(message), 
            "Message should be rejected immediately with REJECT_IMMEDIATELY strategy");
        System.out.println("✅ REJECT_IMMEDIATELY strategy working correctly");
        
        rejectMember.close();
        
        System.out.println("⚙️ ===== FILTER ERROR STRATEGIES TEST COMPLETED ===== ⚙️\n");
    }
    
    @Test
    @DisplayName("Test testing configuration for intentional failures")
    void testTestingConfiguration() {
        System.out.println("\n🧪 ===== TESTING CONFIGURATION FOR TESTS ===== 🧪");
        
        // Use testing configuration that handles intentional test failures gracefully
        FilterErrorHandlingConfig testConfig = FilterErrorHandlingConfig.testingConfig();
        
        OutboxConsumerGroupMember<TestMessage> testMember = new OutboxConsumerGroupMember<>(
            "test-consumer",
            "test-group",
            "test-topic",
            message -> CompletableFuture.completedFuture(null),
            message -> { throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Simulated test error"); },
            null, // Mock consumer group not needed for this test
            testConfig
        );
        
        testMember.start();
        
        TestMessage testMessage = new TestMessage("test-1", "Test message");
        Message<TestMessage> message = createMessage("msg-1", testMessage);
        
        System.out.println("📋 Testing configuration handles intentional test failures...");
        assertFalse(testMember.acceptsMessage(message), 
            "Message should be rejected but handled gracefully");
        System.out.println("✅ Testing configuration handles intentional failures without stack traces");
        
        testMember.close();
        
        System.out.println("🧪 ===== TESTING CONFIGURATION TEST COMPLETED ===== 🧪\n");
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
