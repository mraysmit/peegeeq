package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.SimpleMessage;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.resilience.FilterCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced tests for circuit breaker recovery cycles.
 * Validates state transitions, recovery success rates, and proper behavior under various failure patterns.
 */
public class CircuitBreakerRecoveryTest {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerRecoveryTest.class);
    
    @Test
    @DisplayName("ENHANCED: Circuit breaker complete recovery cycle")
    void testCompleteRecoveryCycle() throws InterruptedException {
        logger.info("🔄 ENHANCED TEST: Complete circuit breaker recovery cycle");
        
        AtomicInteger filterCallCount = new AtomicInteger(0);
        AtomicInteger phase = new AtomicInteger(1); // 1=fail, 2=recover
        AtomicReference<FilterCircuitBreaker.State> lastObservedState = new AtomicReference<>();
        
        // Filter that fails initially, then recovers
        Predicate<Message<TestMessage>> recoveringFilter = message -> {
            int call = filterCallCount.incrementAndGet();
            int currentPhase = phase.get();
            
            logger.debug("Filter call #{} in phase {}", call, currentPhase);
            
            if (currentPhase == 1) {
                // Phase 1: Fail to trigger circuit breaker
                logger.debug("🧪 PHASE 1: Failing call {} to trigger circuit breaker", call);
                throw new RuntimeException("🧪 INTENTIONAL: System overload - phase 1");
            } else {
                // Phase 2: Recovery - filter works normally
                logger.debug("✅ PHASE 2: Filter working normally for call {}", call);
                return true;
            }
        };
        
        MessageHandler<TestMessage> handler = message -> {
            logger.debug("✅ Processing recovered message: {}", message.getId());
            return CompletableFuture.completedFuture(null);
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(3)
            .circuitBreakerMinimumRequests(3)
            .circuitBreakerTimeout(Duration.ofMillis(200)) // Short timeout for testing
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "recovery-test", "recovery-group", "recovery-topic",
            handler, recoveringFilter, null, config
        );
        
        member.start();
        
        // Phase 1: Trigger circuit breaker opening
        logger.info("🔥 PHASE 1: Triggering circuit breaker opening");
        for (int i = 1; i <= 5; i++) {
            TestMessage payload = new TestMessage("fail-" + i, "Failing message " + i);
            Message<TestMessage> message = new SimpleMessage<>("fail-" + i, "recovery-topic", payload);
            
            boolean accepted = member.acceptsMessage(message);
            FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
            lastObservedState.set(metrics.getState());
            
            logger.debug("Message {}: accepted={}, CB state={}, failures={}/{}", 
                i, accepted, metrics.getState(), metrics.getFailureCount(), metrics.getRequestCount());
            
            if (metrics.getState() == FilterCircuitBreaker.State.OPEN) {
                logger.info("⚡ Circuit breaker opened after {} messages", i);
                break;
            }
        }
        
        // Verify circuit breaker is open
        FilterCircuitBreaker.CircuitBreakerMetrics openMetrics = member.getFilterCircuitBreakerMetrics();
        assertEquals(FilterCircuitBreaker.State.OPEN, openMetrics.getState(), 
            "Circuit breaker should be OPEN");
        
        // Test fast-fail behavior while open
        logger.info("⚡ Testing fast-fail behavior while circuit is open");
        TestMessage fastFailMessage = new TestMessage("fast-fail", "Fast fail test");
        Message<TestMessage> fastFailMsg = new SimpleMessage<>("fast-fail", "recovery-topic", fastFailMessage);
        
        int callsBeforeFastFail = filterCallCount.get();
        boolean fastFailAccepted = member.acceptsMessage(fastFailMsg);
        int callsAfterFastFail = filterCallCount.get();
        
        assertFalse(fastFailAccepted, "Message should be rejected by open circuit breaker");
        assertEquals(callsBeforeFastFail, callsAfterFastFail, 
            "Filter should not be called when circuit breaker is open");
        
        // Switch to recovery phase
        logger.info("🔄 SWITCHING TO RECOVERY PHASE");
        phase.set(2);
        
        // Wait for circuit breaker timeout
        logger.info("⏳ Waiting for circuit breaker timeout...");
        Thread.sleep(250); // Wait longer than timeout
        
        // Phase 2: Test recovery - should transition to HALF_OPEN
        logger.info("🔄 PHASE 2: Testing recovery (should transition to HALF_OPEN)");
        TestMessage recoveryMessage = new TestMessage("recovery-1", "Recovery test message");
        Message<TestMessage> recoveryMsg = new SimpleMessage<>("recovery-1", "recovery-topic", recoveryMessage);
        
        boolean recoveryAccepted = member.acceptsMessage(recoveryMsg);
        FilterCircuitBreaker.CircuitBreakerMetrics recoveryMetrics = member.getFilterCircuitBreakerMetrics();
        
        logger.info("🔄 RECOVERY TEST RESULTS:");
        logger.info("   Total filter calls: {}", filterCallCount.get());
        logger.info("   Final phase: {}", phase.get());
        logger.info("   Recovery message accepted: {}", recoveryAccepted);
        logger.info("   Circuit breaker state after recovery: {}", recoveryMetrics.getState());
        logger.info("   Circuit breaker metrics: {}", recoveryMetrics);
        
        // Verify recovery
        assertTrue(recoveryAccepted, "Recovery message should be accepted");
        assertEquals(2, phase.get(), "Should be in recovery phase");
        
        // The circuit breaker should either be CLOSED (successful recovery) or HALF_OPEN (testing)
        assertTrue(recoveryMetrics.getState() == FilterCircuitBreaker.State.CLOSED || 
                  recoveryMetrics.getState() == FilterCircuitBreaker.State.HALF_OPEN,
            "Circuit breaker should be CLOSED or HALF_OPEN after successful recovery, but was: " + recoveryMetrics.getState());
        
        // Test multiple successful operations to ensure stable recovery
        logger.info("🔄 Testing stable recovery with multiple operations");
        for (int i = 2; i <= 5; i++) {
            TestMessage stableMessage = new TestMessage("stable-" + i, "Stable recovery test " + i);
            Message<TestMessage> stableMsg = new SimpleMessage<>("stable-" + i, "recovery-topic", stableMessage);
            
            boolean stableAccepted = member.acceptsMessage(stableMsg);
            assertTrue(stableAccepted, "Stable recovery message " + i + " should be accepted");
        }
        
        FilterCircuitBreaker.CircuitBreakerMetrics finalMetrics = member.getFilterCircuitBreakerMetrics();
        logger.info("   Final circuit breaker state: {}", finalMetrics.getState());
        logger.info("   Final metrics: {}", finalMetrics);
        
        // After multiple successful operations, circuit should be fully closed
        assertEquals(FilterCircuitBreaker.State.CLOSED, finalMetrics.getState(),
            "Circuit breaker should be CLOSED after multiple successful operations");
        
        member.close();
        logger.info("✅ ENHANCED CIRCUIT BREAKER RECOVERY TEST PASSED");
    }
    
    @Test
    @DisplayName("ENHANCED: Circuit breaker partial recovery failure")
    void testPartialRecoveryFailure() throws InterruptedException {
        logger.info("🔄 ENHANCED TEST: Circuit breaker partial recovery failure");
        
        AtomicInteger filterCallCount = new AtomicInteger(0);
        AtomicInteger phase = new AtomicInteger(1); // 1=fail, 2=partial_recovery, 3=full_recovery
        
        // Filter that fails, partially recovers (fails again), then fully recovers
        Predicate<Message<TestMessage>> partialRecoveryFilter = message -> {
            int call = filterCallCount.incrementAndGet();
            int currentPhase = phase.get();
            
            logger.debug("Filter call #{} in phase {}", call, currentPhase);
            
            switch (currentPhase) {
                case 1:
                    // Phase 1: Initial failures
                    logger.debug("🧪 PHASE 1: Initial failure call {}", call);
                    throw new RuntimeException("🧪 INTENTIONAL: Initial system failure");
                    
                case 2:
                    // Phase 2: Partial recovery - fail the recovery test
                    logger.debug("🧪 PHASE 2: Partial recovery failure call {}", call);
                    throw new RuntimeException("🧪 INTENTIONAL: Recovery test failed");
                    
                case 3:
                    // Phase 3: Full recovery
                    logger.debug("✅ PHASE 3: Full recovery call {}", call);
                    return true;
                    
                default:
                    throw new RuntimeException("Unknown phase: " + currentPhase);
            }
        };
        
        MessageHandler<TestMessage> handler = message -> {
            logger.debug("✅ Processing message: {}", message.getId());
            return CompletableFuture.completedFuture(null);
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(3)
            .circuitBreakerMinimumRequests(3)
            .circuitBreakerTimeout(Duration.ofMillis(200))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "partial-recovery-test", "partial-recovery-group", "partial-recovery-topic",
            handler, partialRecoveryFilter, null, config
        );
        
        member.start();
        
        // Phase 1: Trigger circuit breaker opening
        logger.info("🔥 PHASE 1: Triggering initial circuit breaker opening");
        for (int i = 1; i <= 5; i++) {
            TestMessage payload = new TestMessage("initial-fail-" + i, "Initial failing message " + i);
            Message<TestMessage> message = new SimpleMessage<>("initial-fail-" + i, "partial-recovery-topic", payload);
            
            boolean accepted = member.acceptsMessage(message);
            FilterCircuitBreaker.CircuitBreakerMetrics metrics = member.getFilterCircuitBreakerMetrics();
            
            if (metrics.getState() == FilterCircuitBreaker.State.OPEN) {
                logger.info("⚡ Circuit breaker opened after {} messages", i);
                break;
            }
        }
        
        // Switch to partial recovery phase
        phase.set(2);
        Thread.sleep(250); // Wait for timeout
        
        // Phase 2: Attempt recovery that will fail
        logger.info("🔄 PHASE 2: Attempting partial recovery (will fail)");
        TestMessage partialRecoveryMessage = new TestMessage("partial-recovery", "Partial recovery test");
        Message<TestMessage> partialRecoveryMsg = new SimpleMessage<>("partial-recovery", "partial-recovery-topic", partialRecoveryMessage);
        
        boolean partialRecoveryAccepted = member.acceptsMessage(partialRecoveryMsg);
        FilterCircuitBreaker.CircuitBreakerMetrics partialMetrics = member.getFilterCircuitBreakerMetrics();
        
        logger.info("   Partial recovery accepted: {}", partialRecoveryAccepted);
        logger.info("   Circuit breaker state after partial recovery: {}", partialMetrics.getState());
        
        // Should be rejected and circuit should be open again
        assertFalse(partialRecoveryAccepted, "Partial recovery should fail");
        assertEquals(FilterCircuitBreaker.State.OPEN, partialMetrics.getState(),
            "Circuit breaker should be OPEN again after failed recovery");
        
        // Switch to full recovery phase
        phase.set(3);
        Thread.sleep(250); // Wait for timeout again
        
        // Phase 3: Successful recovery
        logger.info("🔄 PHASE 3: Attempting full recovery (should succeed)");
        TestMessage fullRecoveryMessage = new TestMessage("full-recovery", "Full recovery test");
        Message<TestMessage> fullRecoveryMsg = new SimpleMessage<>("full-recovery", "partial-recovery-topic", fullRecoveryMessage);
        
        boolean fullRecoveryAccepted = member.acceptsMessage(fullRecoveryMsg);
        FilterCircuitBreaker.CircuitBreakerMetrics finalMetrics = member.getFilterCircuitBreakerMetrics();
        
        logger.info("🔄 FINAL RECOVERY RESULTS:");
        logger.info("   Total filter calls: {}", filterCallCount.get());
        logger.info("   Full recovery accepted: {}", fullRecoveryAccepted);
        logger.info("   Final circuit breaker state: {}", finalMetrics.getState());
        logger.info("   Final metrics: {}", finalMetrics);
        
        // Verify successful recovery
        assertTrue(fullRecoveryAccepted, "Full recovery should succeed");
        assertNotEquals(FilterCircuitBreaker.State.OPEN, finalMetrics.getState(),
            "Circuit breaker should not be OPEN after successful recovery");
        
        member.close();
        logger.info("✅ ENHANCED PARTIAL RECOVERY TEST PASSED");
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
