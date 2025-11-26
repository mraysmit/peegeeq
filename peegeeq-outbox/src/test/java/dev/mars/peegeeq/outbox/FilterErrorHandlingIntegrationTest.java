package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import dev.mars.peegeeq.test.categories.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that validate the complete filter error handling system
 * under realistic production scenarios.
 */
@Tag(TestCategories.CORE)
public class FilterErrorHandlingIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(FilterErrorHandlingIntegrationTest.class);
    
    @Test
    @DisplayName("INTEGRATION: Production-like scenario with mixed error types")
    void testProductionLikeScenario() throws InterruptedException {
        logger.info("🏭 INTEGRATION TEST: Production-like mixed error scenario");
        
        // Counters for different scenarios
        AtomicInteger totalMessagesSent = new AtomicInteger(0);
        AtomicInteger totalMessagesReachedFilter = new AtomicInteger(0);
        AtomicInteger transientErrors = new AtomicInteger(0);
        AtomicInteger permanentErrors = new AtomicInteger(0);
        AtomicInteger successfulProcessing = new AtomicInteger(0);
        AtomicInteger circuitBreakerRejections = new AtomicInteger(0);
        
        CountDownLatch processingComplete = new CountDownLatch(50); // Expect 50 successful processes
        
        // Realistic filter that simulates various error conditions
        // *** INTENTIONAL TEST FAILURE: This filter deliberately fails for certain message IDs to test error handling ***
        Predicate<Message<TestMessage>> realisticFilter = message -> {
            totalMessagesReachedFilter.incrementAndGet();
            String messageId = message.getId();

            // Simulate different error patterns based on message ID
            if (messageId.contains("timeout")) {
                transientErrors.incrementAndGet();
                logger.info("🧪 INTENTIONAL TEST FAILURE: Network timeout for message {} (THIS IS EXPECTED)", messageId);
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: Connection timeout - please retry (THIS IS EXPECTED)");
            }

            if (messageId.contains("invalid")) {
                permanentErrors.incrementAndGet();
                logger.info("🧪 INTENTIONAL TEST FAILURE: Invalid data for message {} (THIS IS EXPECTED)", messageId);
                throw new IllegalArgumentException("🧪 INTENTIONAL TEST FAILURE: Invalid message format - permanent error (THIS IS EXPECTED)");
            }
            
            if (messageId.contains("unauthorized")) {
                permanentErrors.incrementAndGet();
                logger.debug("🧪 SIMULATED: Security violation for message {}", messageId);
                throw new SecurityException("Unauthorized access - permanent error");
            }
            
            // Normal processing
            return true;
        };
        
        // Handler that processes accepted messages
        MessageHandler<TestMessage> productionHandler = message -> {
            successfulProcessing.incrementAndGet();
            processingComplete.countDown();
            logger.debug("✅ Successfully processed: {}", message.getId());
            return CompletableFuture.completedFuture(null);
        };
        
        // Production-like configuration
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            // Transient error patterns
            .addTransientErrorPattern("timeout")
            .addTransientErrorPattern("connection")
            .addTransientErrorPattern("network")
            
            // Permanent error patterns  
            .addPermanentErrorPattern("invalid")
            .addPermanentErrorPattern("unauthorized")
            .addPermanentErrorPattern("forbidden")
            
            // Exception type classification
            .addTransientExceptionType(java.net.SocketTimeoutException.class)
            .addPermanentExceptionType(IllegalArgumentException.class)
            .addPermanentExceptionType(SecurityException.class)
            
            // Circuit breaker configuration
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(5)
            .circuitBreakerMinimumRequests(10)
            .circuitBreakerTimeout(Duration.ofSeconds(2))
            
            // Retry configuration
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(50))
            .retryBackoffMultiplier(2.0)
            
            // Strategy configuration
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            
            .build();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "production-test", "prod-group", "prod-topic",
            productionHandler, realisticFilter, null, config
        );
        
        member.start();
        
        // Send a realistic mix of messages
        String[] messageTypes = {
            "normal-msg-", "timeout-msg-", "invalid-msg-", "unauthorized-msg-", "normal-msg-"
        };
        
        int messageCount = 0;
        for (int batch = 0; batch < 20; batch++) {
            for (String type : messageTypes) {
                messageCount++;
                String messageId = type + messageCount;
                TestMessage payload = new TestMessage(messageId, "Content for " + messageId);
                Message<TestMessage> message = new SimpleMessage<>(messageId, "prod-topic", payload);

                totalMessagesSent.incrementAndGet();
                boolean accepted = member.acceptsMessage(message);
                if (accepted) {
                    member.processMessage(message);
                } else {
                    // Check if rejection was due to circuit breaker
                    FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
                    if (metrics.getState() == FilterCircuitBreaker.State.OPEN) {
                        circuitBreakerRejections.incrementAndGet();
                        logger.debug("⚡ Circuit breaker rejected: {}", messageId);
                    } else {
                        logger.debug("❌ Filter rejected: {}", messageId);
                    }
                }
            }
        }
        
        // Wait for processing to complete (with timeout)
        boolean completed = processingComplete.await(10, TimeUnit.SECONDS);
        
        // Get final metrics
        FilterCircuitBreaker.CircuitBreakerMetrics finalMetrics = member.getFilterCircuitBreakerMetrics();
        
        logger.info("🏭 PRODUCTION INTEGRATION TEST RESULTS:");
        logger.info("   Total messages sent: {}", totalMessagesSent.get());
        logger.info("   Messages reaching filter: {}", totalMessagesReachedFilter.get());
        logger.info("   Transient errors: {}", transientErrors.get());
        logger.info("   Permanent errors: {}", permanentErrors.get());
        logger.info("   Successful processing: {}", successfulProcessing.get());
        logger.info("   Circuit breaker rejections: {}", circuitBreakerRejections.get());
        logger.info("   Circuit breaker final state: {}", finalMetrics.getState());
        logger.info("   Processing completed: {}", completed);

        // Critical validations
        assertTrue(totalMessagesReachedFilter.get() > 0, "Some messages should reach the filter");
        assertTrue(successfulProcessing.get() > 0, "Some messages should be processed successfully");
        
        // Verify error classification is working
        assertTrue(transientErrors.get() > 0, "Should have some transient errors");
        assertTrue(permanentErrors.get() > 0, "Should have some permanent errors");
        
        // Verify no message loss - all messages are accounted for
        int totalAccountedFor = successfulProcessing.get() + transientErrors.get() +
                               permanentErrors.get() + circuitBreakerRejections.get();

        logger.info("   Total messages accounted for: {}", totalAccountedFor);

        // The total accounted for should equal the total messages sent
        // All messages should be either processed, rejected by filter, or rejected by circuit breaker
        assertTrue(totalAccountedFor == totalMessagesSent.get(),
            "All sent messages should be accounted for: sent=" + totalMessagesSent.get() +
            ", accounted=" + totalAccountedFor);
        
        member.close();
        logger.info("✅ PRODUCTION INTEGRATION TEST PASSED");
    }
    
    @Test
    @DisplayName("INTEGRATION: Circuit breaker recovery cycle")
    void testCircuitBreakerRecoveryCycle() throws InterruptedException {
        logger.info("🔄 INTEGRATION TEST: Circuit breaker recovery cycle");
        
        AtomicInteger filterCalls = new AtomicInteger(0);
        AtomicInteger phase = new AtomicInteger(1); // 1=fail, 2=recover
        
        // Filter that fails initially, then recovers
        // *** INTENTIONAL TEST FAILURE: This filter deliberately fails in phase 1 to test circuit breaker recovery ***
        Predicate<Message<TestMessage>> recoveringFilter = message -> {
            int call = filterCalls.incrementAndGet();
            int currentPhase = phase.get();

            if (currentPhase == 1) {
                // Phase 1: Always fail to trigger circuit breaker
                logger.info("🧪 INTENTIONAL TEST FAILURE: PHASE 1 - Failing call {} to trigger circuit breaker (THIS IS EXPECTED)", call);
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: System overload - phase 1 (THIS IS EXPECTED)");
            }

            // Phase 2: Recovery - filter works normally
            logger.info("✅ PHASE 2: Filter working normally for call {} (test recovery working)", call);
            return true;
        };
        
        MessageHandler<TestMessage> handler = message -> {
            logger.debug("✅ Processing recovered message: {}", message.getId());
            return CompletableFuture.completedFuture(null);
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(5)
            .circuitBreakerMinimumRequests(5)
            .circuitBreakerTimeout(Duration.ofMillis(500)) // Short timeout for testing
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "recovery-test", "recovery-group", "recovery-topic",
            handler, recoveringFilter, null, config
        );
        
        member.start();
        
        // Phase 1: Send messages to trigger circuit breaker
        logger.info("🔥 PHASE 1: Triggering circuit breaker");
        for (int i = 1; i <= 10; i++) {
            TestMessage payload = new TestMessage("fail-" + i, "Failing message " + i);
            Message<TestMessage> message = new SimpleMessage<>("fail-" + i, "recovery-topic", payload);
            
            boolean accepted = member.acceptsMessage(message);
            FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
            
            logger.debug("Message {}: accepted={}, CB state={}", i, accepted, metrics.getState());
            
            if (metrics.getState() == FilterCircuitBreaker.State.OPEN) {
                logger.info("⚡ Circuit breaker opened after {} messages", i);
                break;
            }
        }
        
        // Verify circuit breaker is open
        FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
        assertEquals(FilterCircuitBreaker.State.OPEN, metrics.getState(),
            "Circuit breaker should be open");

        // Switch to recovery phase BEFORE testing recovery
        logger.info("🔄 SWITCHING TO RECOVERY PHASE");
        phase.set(2);

        // Wait for circuit breaker timeout
        logger.info("⏳ Waiting for circuit breaker timeout...");
        Thread.sleep(600); // Wait longer than timeout

        // Phase 2: Test recovery
        logger.info("🔄 PHASE 2: Testing recovery");
        TestMessage recoveryPayload = new TestMessage("recovery-1", "Recovery test message");
        Message<TestMessage> recoveryMessage = new SimpleMessage<>("recovery-1", "recovery-topic", recoveryPayload);
        
        boolean recoveryAccepted = member.acceptsMessage(recoveryMessage);
        FilterCircuitBreaker.CircuitBreakerMetrics finalMetrics = member.getFilterCircuitBreakerMetrics();
        
        logger.info("🔄 RECOVERY TEST RESULTS:");
        logger.info("   Total filter calls: {}", filterCalls.get());
        logger.info("   Final phase: {}", phase.get());
        logger.info("   Recovery message accepted: {}", recoveryAccepted);
        logger.info("   Final circuit breaker state: {}", finalMetrics.getState());
        
        // Verify recovery
        assertTrue(recoveryAccepted, "Recovery message should be accepted");
        assertEquals(2, phase.get(), "Should be in recovery phase");
        assertNotEquals(FilterCircuitBreaker.State.OPEN, finalMetrics.getState(), 
            "Circuit breaker should not be open after recovery");
        
        member.close();
        logger.info("✅ CIRCUIT BREAKER RECOVERY TEST PASSED");
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
