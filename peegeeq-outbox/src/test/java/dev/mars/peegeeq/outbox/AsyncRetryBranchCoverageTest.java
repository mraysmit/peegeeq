package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.outbox.resilience.AsyncFilterRetryManager;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
public class AsyncRetryBranchCoverageTest {

    @Test
    @DisplayName("BRANCH: DEAD_LETTER_IMMEDIATELY strategy")
    void testDeadLetterImmediatelyStrategy() throws Exception {
        // Filter that always fails
        Predicate<Message<TestMessage>> failingFilter = message -> {
            throw new RuntimeException("Immediate DLQ error");
        };

        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            // Do not classify as transient, so it falls back to default strategy (DEAD_LETTER_IMMEDIATELY)
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("dlq-topic")
            .build();

        // Mock/Fake DLQ Manager
        FakeDeadLetterQueueManager fakeDlq = new FakeDeadLetterQueueManager(config);
        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config, fakeDlq);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);

        Message<TestMessage> message = new SimpleMessage<>("msg-1", "topic", new TestMessage("1", "payload"));

        CompletableFuture<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, failingFilter, circuitBreaker);

        AsyncFilterRetryManager.FilterResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals(AsyncFilterRetryManager.FilterResult.Status.DEAD_LETTER, result.getStatus());
        assertEquals(1, result.getAttempts());
        assertTrue(fakeDlq.sendCalled);
    }

    @Test
    @DisplayName("BRANCH: RETRY_THEN_REJECT exhaustion")
    void testRetryThenRejectExhaustion() throws Exception {
        Predicate<Message<TestMessage>> failingFilter = message -> {
            throw new RuntimeException("Retry then reject error");
        };

        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(1))
            .build();

        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);

        Message<TestMessage> message = new SimpleMessage<>("msg-2", "topic", new TestMessage("2", "payload"));

        CompletableFuture<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, failingFilter, circuitBreaker);

        AsyncFilterRetryManager.FilterResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals(AsyncFilterRetryManager.FilterResult.Status.REJECTED, result.getStatus());
        assertEquals(3, result.getAttempts()); // Initial + 2 retries
    }

    @Test
    @DisplayName("BRANCH: Max retry delay cap")
    void testMaxRetryDelayCap() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        Predicate<Message<TestMessage>> failingFilter = message -> {
            attempts.incrementAndGet();
            throw new RuntimeException("Delay cap error");
        };

        // Config: Initial 10ms, Multiplier 10, Max 15ms.
        // Attempt 1: 0 delay.
        // Attempt 2: 10ms delay.
        // Attempt 3: 10 * 10 = 100ms -> capped at 15ms.
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .retryBackoffMultiplier(10.0)
            .maxRetryDelay(Duration.ofMillis(15))
            .build();

        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);

        Message<TestMessage> message = new SimpleMessage<>("msg-3", "topic", new TestMessage("3", "payload"));

        long start = System.currentTimeMillis();
        CompletableFuture<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, failingFilter, circuitBreaker);

        future.get(5, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - start;

        // Expected duration: ~10ms (1st retry) + ~15ms (2nd retry) + execution overhead.
        // If not capped, it would be 10ms + 100ms = 110ms.
        // So if duration < 100ms, we know capping worked (allowing for system overhead).
        assertTrue(duration < 100, "Duration " + duration + "ms suggests max delay cap was ignored");
    }

    @Test
    @DisplayName("BRANCH: DLQ Failure")
    void testDeadLetterQueueFailure() throws Exception {
        Predicate<Message<TestMessage>> failingFilter = message -> {
            throw new RuntimeException("DLQ failure error");
        };

        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
            .deadLetterQueueEnabled(true)
            .build();

        // Fake DLQ that fails
        FakeDeadLetterQueueManager failingDlq = new FakeDeadLetterQueueManager(config) {
            @Override
            public <T> CompletableFuture<Void> sendToDeadLetter(Message<T> message, String filterId, String reason, int attempts, FilterErrorHandlingConfig.ErrorClassification classification, Exception originalException) {
                return CompletableFuture.failedFuture(new RuntimeException("DLQ unavailable"));
            }
        };

        AsyncFilterRetryManager retryManager = new AsyncFilterRetryManager("test-filter", config, failingDlq);
        FilterCircuitBreaker circuitBreaker = new FilterCircuitBreaker("test-filter", config);

        Message<TestMessage> message = new SimpleMessage<>("msg-4", "topic", new TestMessage("4", "payload"));

        CompletableFuture<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, failingFilter, circuitBreaker);

        AsyncFilterRetryManager.FilterResult result = future.get(5, TimeUnit.SECONDS);

        // Should still be DEAD_LETTER status, but with error message in reason
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.DEAD_LETTER, result.getStatus());
        assertTrue(result.getReason().contains("DLQ failed"));
    }

    // Helper class
    static class FakeDeadLetterQueueManager extends DeadLetterQueueManager {
        boolean sendCalled = false;

        public FakeDeadLetterQueueManager(FilterErrorHandlingConfig config) {
            super(config);
        }

        @Override
        public <T> CompletableFuture<Void> sendToDeadLetter(Message<T> message, String filterId, String reason, int attempts, FilterErrorHandlingConfig.ErrorClassification classification, Exception originalException) {
            sendCalled = true;
            return CompletableFuture.completedFuture(null);
        }
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
