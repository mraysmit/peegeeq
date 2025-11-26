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
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for filter error handling under various error conditions.
 * Measures throughput, latency, and resource usage during different failure scenarios.
 */
@Tag(TestCategories.PERFORMANCE)
public class FilterErrorPerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(FilterErrorPerformanceTest.class);
    
    @Test
    @DisplayName("PERFORMANCE: Throughput under normal conditions")
    void testNormalThroughputBaseline() throws InterruptedException {
        logger.info("📊 PERFORMANCE TEST: Normal throughput baseline");
        
        int messageCount = 1000;
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);
        CountDownLatch completionLatch = new CountDownLatch(messageCount);
        
        // Normal filter that always accepts
        Predicate<Message<TestMessage>> normalFilter = message -> true;
        
        // Handler that tracks processing time
        MessageHandler<TestMessage> performanceHandler = message -> {
            long startTime = System.nanoTime();
            return CompletableFuture.supplyAsync(() -> {
                // Simulate some processing work
                try {
                    Thread.sleep(1); // 1ms processing time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                long endTime = System.nanoTime();
                totalProcessingTime.addAndGet(endTime - startTime);
                processedCount.incrementAndGet();
                completionLatch.countDown();
                
                return null;
            });
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.defaultConfig();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "performance-baseline", "perf-group", "perf-topic",
            performanceHandler, normalFilter, null, config
        );
        
        member.start();
        
        // Measure throughput
        Instant startTime = Instant.now();
        
        for (int i = 1; i <= messageCount; i++) {
            TestMessage payload = new TestMessage("msg-" + i, "Performance test message " + i);
            Message<TestMessage> message = new SimpleMessage<>("msg-" + i, "perf-topic", payload);
            
            boolean accepted = member.acceptsMessage(message);
            if (accepted) {
                member.processMessage(message);
            }
        }
        
        // Wait for completion
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        
        Instant endTime = Instant.now();
        Duration totalTime = Duration.between(startTime, endTime);
        
        // Calculate metrics
        double throughputPerSecond = messageCount / (totalTime.toMillis() / 1000.0);
        double avgProcessingTimeMs = totalProcessingTime.get() / (1_000_000.0 * processedCount.get());
        
        logger.info("📊 NORMAL THROUGHPUT BASELINE RESULTS:");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Total time: {} ms", totalTime.toMillis());
        logger.info("   Throughput: {:.2f} messages/second", throughputPerSecond);
        logger.info("   Average processing time: {:.2f} ms", avgProcessingTimeMs);
        
        // Assertions
        assertEquals(messageCount, processedCount.get(), "All messages should be processed");
        assertTrue(throughputPerSecond > 100, "Throughput should be reasonable (>100 msg/sec)");
        
        member.close();
        logger.info("✅ NORMAL THROUGHPUT BASELINE COMPLETED");
    }
    
    @Test
    @DisplayName("PERFORMANCE: Throughput under filter exceptions")
    void testThroughputUnderFilterExceptions() throws InterruptedException {
        logger.info("📊 PERFORMANCE TEST: Throughput under filter exceptions");
        
        int messageCount = 1000;
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicInteger filterExceptions = new AtomicInteger(0);
        CountDownLatch completionLatch = new CountDownLatch(messageCount);
        
        // Filter that fails on every 5th message
        Predicate<Message<TestMessage>> intermittentFailingFilter = message -> {
            String messageId = message.getId();
            int messageNum = Integer.parseInt(messageId.replace("msg-", ""));
            
            if (messageNum % 5 == 0) {
                filterExceptions.incrementAndGet();
                throw new RuntimeException("🧪 PERFORMANCE TEST: Intermittent filter failure");
            }
            
            return true;
        };
        
        // Handler that tracks processing
        MessageHandler<TestMessage> performanceHandler = message -> {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(1); // 1ms processing time
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                processedCount.incrementAndGet();
                return null;
            });
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .circuitBreakerEnabled(false) // Disable for pure filter exception testing
            .build();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "performance-exceptions", "perf-group", "perf-topic",
            performanceHandler, intermittentFailingFilter, null, config
        );
        
        member.start();
        
        // Measure throughput under exceptions
        Instant startTime = Instant.now();
        
        for (int i = 1; i <= messageCount; i++) {
            TestMessage payload = new TestMessage("msg-" + i, "Performance test message " + i);
            Message<TestMessage> message = new SimpleMessage<>("msg-" + i, "perf-topic", payload);
            
            boolean accepted = member.acceptsMessage(message);
            if (accepted) {
                member.processMessage(message).whenComplete((result, throwable) -> {
                    completionLatch.countDown();
                });
            } else {
                rejectedCount.incrementAndGet();
                completionLatch.countDown();
            }
        }
        
        // Wait for completion
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
            "All messages should be handled within timeout");
        
        Instant endTime = Instant.now();
        Duration totalTime = Duration.between(startTime, endTime);
        
        // Calculate metrics
        double throughputPerSecond = messageCount / (totalTime.toMillis() / 1000.0);
        double rejectionRate = (double) rejectedCount.get() / messageCount * 100;
        
        logger.info("📊 FILTER EXCEPTION PERFORMANCE RESULTS:");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Messages rejected: {}", rejectedCount.get());
        logger.info("   Filter exceptions: {}", filterExceptions.get());
        logger.info("   Total time: {} ms", totalTime.toMillis());
        logger.info("   Throughput: {:.2f} messages/second", throughputPerSecond);
        logger.info("   Rejection rate: {:.2f}%", rejectionRate);
        
        // Assertions
        assertEquals(messageCount, processedCount.get() + rejectedCount.get(), 
            "All messages should be either processed or rejected");
        assertEquals(200, filterExceptions.get(), "Should have 200 filter exceptions (every 5th message)");
        assertEquals(200, rejectedCount.get(), "Should have 200 rejections");
        assertEquals(800, processedCount.get(), "Should have 800 processed messages");
        assertTrue(throughputPerSecond > 50, "Throughput should be reasonable even with exceptions");
        
        member.close();
        logger.info("✅ FILTER EXCEPTION PERFORMANCE TEST COMPLETED");
    }
    
    @Test
    @DisplayName("PERFORMANCE: Circuit breaker impact on throughput")
    void testCircuitBreakerPerformanceImpact() throws InterruptedException {
        logger.info("📊 PERFORMANCE TEST: Circuit breaker impact on throughput");
        
        int messageCount = 1000;
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        AtomicInteger circuitBreakerRejections = new AtomicInteger(0);
        AtomicInteger filterCalls = new AtomicInteger(0);
        CountDownLatch completionLatch = new CountDownLatch(messageCount);
        
        // Filter that fails initially to trigger circuit breaker
        // *** INTENTIONAL TEST FAILURE: This filter deliberately fails first 50 messages to test circuit breaker performance ***
        Predicate<Message<TestMessage>> circuitBreakerFilter = message -> {
            int calls = filterCalls.incrementAndGet();

            // Fail first 50 messages to trigger circuit breaker
            if (calls <= 50) {
                if (calls % 10 == 1) { // Log every 10th failure to avoid spam
                    logger.info("🧪 INTENTIONAL TEST FAILURE: PERFORMANCE TEST circuit breaker trigger failure #{} (THIS IS EXPECTED)", calls);
                }
                throw new RuntimeException("🧪 INTENTIONAL TEST FAILURE: PERFORMANCE TEST - Circuit breaker trigger failure (THIS IS EXPECTED)");
            }

            // After circuit breaker opens, this won't be called much
            return true;
        };
        
        MessageHandler<TestMessage> performanceHandler = message -> {
            return CompletableFuture.supplyAsync(() -> {
                processedCount.incrementAndGet();
                return null;
            });
        };
        
        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .circuitBreakerEnabled(true)
            .circuitBreakerFailureThreshold(5)
            .circuitBreakerMinimumRequests(5)
            .circuitBreakerTimeout(Duration.ofSeconds(10)) // Long timeout to keep circuit open
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "performance-circuit-breaker", "perf-group", "perf-topic",
            performanceHandler, circuitBreakerFilter, null, config
        );
        
        member.start();
        
        // Measure throughput with circuit breaker
        Instant startTime = Instant.now();
        
        for (int i = 1; i <= messageCount; i++) {
            TestMessage payload = new TestMessage("cb-msg-" + i, "Circuit breaker test " + i);
            Message<TestMessage> message = new SimpleMessage<>("cb-msg-" + i, "perf-topic", payload);
            
            // Check circuit breaker state before processing
            FilterCircuitBreaker.CircuitBreakerMetrics preMetrics = member.getFilterCircuitBreakerMetrics();
            boolean circuitWasOpen = preMetrics.getState() == FilterCircuitBreaker.State.OPEN;
            
            boolean accepted = member.acceptsMessage(message);
            if (accepted) {
                member.processMessage(message).whenComplete((result, throwable) -> {
                    completionLatch.countDown();
                });
            } else {
                rejectedCount.incrementAndGet();
                if (circuitWasOpen) {
                    circuitBreakerRejections.incrementAndGet();
                }
                completionLatch.countDown();
            }
        }
        
        // Wait for completion
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
            "All messages should be handled within timeout");
        
        Instant endTime = Instant.now();
        Duration totalTime = Duration.between(startTime, endTime);
        
        FilterCircuitBreaker.CircuitBreakerMetrics finalMetrics = member.getFilterCircuitBreakerMetrics();
        
        // Calculate metrics
        double throughputPerSecond = messageCount / (totalTime.toMillis() / 1000.0);
        double circuitBreakerEfficiency = (double) circuitBreakerRejections.get() / rejectedCount.get() * 100;
        
        logger.info("📊 CIRCUIT BREAKER PERFORMANCE RESULTS:");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Messages rejected: {}", rejectedCount.get());
        logger.info("   Circuit breaker rejections: {}", circuitBreakerRejections.get());
        logger.info("   Filter calls made: {}", filterCalls.get());
        logger.info("   Total time: {} ms", totalTime.toMillis());
        logger.info("   Throughput: {:.2f} messages/second", throughputPerSecond);
        logger.info("   Circuit breaker efficiency: {:.2f}%", circuitBreakerEfficiency);
        logger.info("   Final circuit breaker state: {}", finalMetrics.getState());
        logger.info("   Final circuit breaker metrics: {}", finalMetrics);
        
        // Assertions
        assertEquals(messageCount, processedCount.get() + rejectedCount.get(), 
            "All messages should be either processed or rejected");
        assertEquals(FilterCircuitBreaker.State.OPEN, finalMetrics.getState(), 
            "Circuit breaker should be open");
        assertTrue(circuitBreakerRejections.get() > 0, "Circuit breaker should reject some messages");
        assertTrue(filterCalls.get() < messageCount, 
            "Circuit breaker should prevent some filter calls (efficiency)");
        assertTrue(throughputPerSecond > 100, "Throughput should be high due to circuit breaker fast-fail");
        
        member.close();
        logger.info("✅ CIRCUIT BREAKER PERFORMANCE TEST COMPLETED");
    }
    
    @Test
    @DisplayName("PERFORMANCE: Dead letter queue performance impact")
    void testDeadLetterQueuePerformanceImpact() throws InterruptedException {
        logger.info("📊 PERFORMANCE TEST: Dead letter queue performance impact");

        int messageCount = 100; // Smaller count for DLQ test
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger deadLetterCount = new AtomicInteger(0);
        CountDownLatch completionLatch = new CountDownLatch(messageCount);

        // Filter that sends every 10th message to dead letter queue
        Predicate<Message<TestMessage>> deadLetterFilter = message -> {
            String messageId = message.getId();
            int messageNum = Integer.parseInt(messageId.replace("dlq-msg-", ""));

            if (messageNum % 10 == 0) {
                throw new IllegalArgumentException("🧪 PERFORMANCE TEST: Permanent error for DLQ");
            }

            return true;
        };

        MessageHandler<TestMessage> performanceHandler = message -> {
            return CompletableFuture.supplyAsync(() -> {
                processedCount.incrementAndGet();
                return null;
            });
        };

        FilterErrorHandlingConfig config = FilterErrorHandlingConfig.builder()
            .addPermanentErrorPattern("PERFORMANCE TEST: Permanent error")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("performance-test-dlq")
            .circuitBreakerEnabled(false)
            .build();

        OutboxConsumerGroupMember<TestMessage> member = new OutboxConsumerGroupMember<>(
            "performance-dlq", "perf-group", "perf-topic",
            performanceHandler, deadLetterFilter, null, config
        );

        member.start();

        // Measure throughput with dead letter queue
        Instant startTime = Instant.now();

        for (int i = 1; i <= messageCount; i++) {
            TestMessage payload = new TestMessage("dlq-msg-" + i, "DLQ performance test " + i);
            Message<TestMessage> message = new SimpleMessage<>("dlq-msg-" + i, "perf-topic", payload);

            boolean accepted = member.acceptsMessage(message);
            if (accepted) {
                member.processMessage(message).whenComplete((result, throwable) -> {
                    completionLatch.countDown();
                });
            } else {
                deadLetterCount.incrementAndGet();
                completionLatch.countDown();
            }
        }

        // Wait for completion
        assertTrue(completionLatch.await(30, TimeUnit.SECONDS),
            "All messages should be handled within timeout");

        Instant endTime = Instant.now();
        Duration totalTime = Duration.between(startTime, endTime);

        // Calculate metrics
        double throughputPerSecond = messageCount / (totalTime.toMillis() / 1000.0);
        double deadLetterRate = (double) deadLetterCount.get() / messageCount * 100;

        logger.info("📊 DEAD LETTER QUEUE PERFORMANCE RESULTS:");
        logger.info("   Messages processed: {}", processedCount.get());
        logger.info("   Messages sent to DLQ: {}", deadLetterCount.get());
        logger.info("   Total time: {} ms", totalTime.toMillis());
        logger.info("   Throughput: {:.2f} messages/second", throughputPerSecond);
        logger.info("   Dead letter rate: {:.2f}%", deadLetterRate);

        // Assertions
        assertEquals(messageCount, processedCount.get() + deadLetterCount.get(),
            "All messages should be either processed or sent to DLQ");
        assertEquals(10, deadLetterCount.get(), "Should have 10 dead letter messages (every 10th)");
        assertEquals(90, processedCount.get(), "Should have 90 processed messages");
        assertTrue(throughputPerSecond > 10, "Throughput should be reasonable with DLQ operations");

        member.close();
        logger.info("✅ DEAD LETTER QUEUE PERFORMANCE TEST COMPLETED");
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
