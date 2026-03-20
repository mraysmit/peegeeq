package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Tag(TestCategories.CORE)
public class AsyncFilterRetryManagerTest {

    private AsyncFilterRetryManager retryManager;
    private FilterErrorHandlingConfig config;
    private DeadLetterQueueManager deadLetterQueueManager;
    private FilterCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        config = FilterErrorHandlingConfig.builder()
                .maxRetries(3)
                .initialRetryDelay(Duration.ofMillis(10))
                .retryBackoffMultiplier(2.0)
                .maxRetryDelay(Duration.ofMillis(100))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
                .build();

        deadLetterQueueManager = new DeadLetterQueueManager(config);
        retryManager = new AsyncFilterRetryManager("test-filter", config, deadLetterQueueManager);
        
        circuitBreaker = new FilterCircuitBreaker("test-cb", 
            FilterErrorHandlingConfig.builder()
                .circuitBreakerEnabled(true)
                .circuitBreakerFailureThreshold(5)
                .circuitBreakerMinimumRequests(5)
                .build());
    }

    @Test
    @DisplayName("Should succeed immediately when filter passes")
    void testSuccessfulExecution() throws Exception {
        Message<String> message = createMessage("test-1");
        Predicate<Message<String>> filter = msg -> true;

        Future<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, filter, circuitBreaker);

        CountDownLatch latch1 = new CountDownLatch(1);
        AtomicReference<AsyncFilterRetryManager.FilterResult> resultRef1 = new AtomicReference<>();
        future.onSuccess(r -> { resultRef1.set(r); latch1.countDown(); }).onFailure(e -> latch1.countDown());
        assertTrue(latch1.await(1, TimeUnit.SECONDS));
        AsyncFilterRetryManager.FilterResult result = resultRef1.get();
        
        assertTrue(result.isAccepted());
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.ACCEPTED, result.getStatus());
        assertEquals(1, result.getAttempts());
    }

    @Test
    @DisplayName("Should retry and eventually succeed")
    void testRetryAndSucceed() throws Exception {
        Message<String> message = createMessage("test-2");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Temporary failure");
            }
            return true;
        };

        Future<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, filter, circuitBreaker);

        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<AsyncFilterRetryManager.FilterResult> resultRef2 = new AtomicReference<>();
        future.onSuccess(r -> { resultRef2.set(r); latch2.countDown(); }).onFailure(e -> latch2.countDown());
        assertTrue(latch2.await(1, TimeUnit.SECONDS));
        AsyncFilterRetryManager.FilterResult result = resultRef2.get();
        
        assertTrue(result.isAccepted());
        assertEquals(AsyncFilterRetryManager.FilterResult.Status.ACCEPTED, result.getStatus());
        assertEquals(3, result.getAttempts());
    }

    @Test
    @DisplayName("Should exhaust retries and fail")
    void testRetryExhaustion() throws Exception {
        Message<String> message = createMessage("test-3");
        
        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("Permanent failure");
        };

        Future<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, filter, circuitBreaker);

        CountDownLatch latch3 = new CountDownLatch(1);
        AtomicReference<AsyncFilterRetryManager.FilterResult> resultRef3 = new AtomicReference<>();
        future.onSuccess(r -> { resultRef3.set(r); latch3.countDown(); }).onFailure(e -> latch3.countDown());
        assertTrue(latch3.await(1, TimeUnit.SECONDS));
        AsyncFilterRetryManager.FilterResult result = resultRef3.get();
        
        assertFalse(result.isAccepted());
        // 1 initial + 3 retries = 4 attempts
        assertEquals(4, result.getAttempts());
    }

    @Test
    @DisplayName("Should respect circuit breaker open state")
    void testCircuitBreakerOpen() throws Exception {
        Message<String> message = createMessage("test-4");
        Predicate<Message<String>> filter = msg -> true;

        // Force open circuit breaker
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }
        assertFalse(circuitBreaker.allowRequest());

        Future<AsyncFilterRetryManager.FilterResult> future = 
            retryManager.executeFilterWithRetry(message, filter, circuitBreaker);

        CountDownLatch latch4 = new CountDownLatch(1);
        AtomicReference<AsyncFilterRetryManager.FilterResult> resultRef4 = new AtomicReference<>();
        future.onSuccess(r -> { resultRef4.set(r); latch4.countDown(); }).onFailure(e -> latch4.countDown());
        assertTrue(latch4.await(1, TimeUnit.SECONDS));
        AsyncFilterRetryManager.FilterResult result = resultRef4.get();
        
        assertFalse(result.isAccepted());
        assertEquals("Circuit breaker open", result.getReason());
    }

    private Message<String> createMessage(String id) {
        return new Message<>() {
            @Override
            public String getId() { return id; }
            @Override
            public String getPayload() { return "payload"; }
            @Override
            public java.util.Map<String, String> getHeaders() { return java.util.Collections.emptyMap(); }
            @Override
            public java.time.Instant getCreatedAt() { return java.time.Instant.now(); }
        };
    }
}
