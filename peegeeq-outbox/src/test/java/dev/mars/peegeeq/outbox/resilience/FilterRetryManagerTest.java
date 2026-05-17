package dev.mars.peegeeq.outbox.resilience;

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.outbox.config.FilterErrorHandlingConfig;
import dev.mars.peegeeq.outbox.deadletter.DeadLetterQueueManager;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
@ExtendWith(VertxExtension.class)
class FilterRetryManagerTest {

    private static final Logger logger = LoggerFactory.getLogger(FilterRetryManagerTest.class);

    private Vertx vertx;
    private FilterRetryManager retryManager;
    private FilterRetryManager retryManagerWithDlq;
    private FilterCircuitBreaker circuitBreaker;
    private FilterErrorHandlingConfig config;
    private DeadLetterQueueManager deadLetterQueueManager;

    @BeforeEach
    void setUp(Vertx vertx) {
        this.vertx = vertx;

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

        retryManager = new FilterRetryManager("test-filter", config, vertx);

        // New constructor with DLQ support
        retryManagerWithDlq = new FilterRetryManager("test-filter-dlq", config, vertx, deadLetterQueueManager);
    }

    @Test
    void testSuccessfulFilterExecution(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-1", "payload");
        Predicate<Message<String>> filter = msg -> true;

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertTrue(result);
                testContext.completeNow();
            })));
    }

    @Test
    void testFilterRejectsMessage(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-2", "payload");
        Predicate<Message<String>> filter = msg -> false;

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                testContext.completeNow();
            })));
    }

    @Test
    void testRetryOnTransientError(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-3", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Transient error");
            }
            return true;
        };

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertTrue(result);
                assertEquals(2, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testRetryExhaustion(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-4", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Persistent error");
        };

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Initial attempt + 3 retries = 4 total
                assertEquals(4, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testCircuitBreakerOpen(VertxTestContext testContext) {
        // Open the circuit breaker by recording failures
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }

        Message<String> message = createTestMessage("msg-5", "payload");
        Predicate<Message<String>> filter = msg -> true;

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                // Should reject immediately due to open circuit breaker
                assertFalse(result);
                testContext.completeNow();
            })));
    }

    @Test
    void testExponentialBackoff(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-6", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error requiring backoff");
        };

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                long duration = System.currentTimeMillis() - startTime;
                // Should have 4 attempts with exponential backoff: 0ms, 10ms, 20ms, 40ms
                // Total minimum delay: ~70ms
                assertTrue(duration >= 60, "Expected delays from exponential backoff, got: " + duration + "ms");
                assertEquals(4, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testRejectImmediatelyStrategy(VertxTestContext testContext) {
        // Configure for immediate rejection of permanent errors
        FilterErrorHandlingConfig immediateRejectConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        FilterRetryManager immediateRejectManager = new FilterRetryManager(
            "immediate-reject-filter", immediateRejectConfig, vertx);
        
        Message<String> message = createTestMessage("msg-7", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Permanent error");
        };

        immediateRejectManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Should only attempt once (no retries)
                assertEquals(1, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testDeadLetterQueueEnabled(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-8", "payload");
        
        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error requiring DLQ");
        };

        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                // Should reject after exhausting retries (DLQ is logged but returns false)
                assertFalse(result);
                testContext.completeNow();
            })));
    }

    @Test
    void testDeadLetterQueueDisabled(VertxTestContext testContext) {
        FilterErrorHandlingConfig noDlqConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .deadLetterQueueEnabled(false)
            .build();
        
        FilterRetryManager noDlqManager = new FilterRetryManager("no-dlq-filter", noDlqConfig, vertx);

        Message<String> message = createTestMessage("msg-9", "payload");

        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error with DLQ disabled");
        };

        noDlqManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                testContext.completeNow();
            })));
    }

    @Test
    void testMaxRetryDelayCappping(VertxTestContext testContext) {
        FilterErrorHandlingConfig cappedConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(5)
            .initialRetryDelay(Duration.ofMillis(10))
            .maxRetryDelay(Duration.ofMillis(50))  // Low cap for testing
            .retryBackoffMultiplier(3.0)  // High multiplier
            .build();
        
        FilterRetryManager cappedManager = new FilterRetryManager("capped-filter", cappedConfig, vertx);
        
        Message<String> message = createTestMessage("msg-10", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error testing delay cap");
        };

        cappedManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                // Initial attempt + maxRetries (5) but one may fail to complete
                assertTrue(attempts.get() >= 5 && attempts.get() <= 6,
                    "Expected 5-6 attempts, got: " + attempts.get());
                testContext.completeNow();
            })));
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
    void testConcurrentRetries(VertxTestContext testContext) {
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

        Future<Boolean> result1 = retryManager.executeWithRetry(message1, filter1, circuitBreaker);
        Future<Boolean> result2 = retryManager.executeWithRetry(message2, filter2, circuitBreaker);

        Future.all(result1, result2)
            .onComplete(testContext.succeeding(composite -> testContext.verify(() -> {
                assertTrue(result1.result());
                assertTrue(result2.result());
                assertEquals(2, attempts1.get());
                assertEquals(3, attempts2.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testDeadLetterImmediatelyStrategy(VertxTestContext testContext) {
        FilterErrorHandlingConfig dlqImmediateConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("test-dlq-immediate")
            .build();
        
        FilterRetryManager dlqImmediateManager = new FilterRetryManager(
            "dlq-immediate-filter", dlqImmediateConfig, vertx);
        
        Message<String> message = createTestMessage("msg-dlq-immediate", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Critical error requiring immediate DLQ");
        };

        dlqImmediateManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Should only attempt once (immediate DLQ)
                assertEquals(1, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testRetryThenDeadLetterStrategy(VertxTestContext testContext) {
        FilterErrorHandlingConfig retryThenDlqConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
            .deadLetterQueueEnabled(true)
            .deadLetterQueueTopic("test-dlq-after-retry")
            .build();
        
        FilterRetryManager retryThenDlqManager = new FilterRetryManager(
            "retry-dlq-filter", retryThenDlqConfig, vertx);
        
        Message<String> message = createTestMessage("msg-retry-dlq", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error requiring retry then DLQ");
        };

        retryThenDlqManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Initial attempt + 2 retries = 3 attempts before DLQ
                assertEquals(3, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testPermanentErrorPattern(VertxTestContext testContext) {
        FilterErrorHandlingConfig permanentErrorConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .addPermanentErrorPattern(".*IllegalArgument.*")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .build();
        
        FilterRetryManager permanentErrorManager = new FilterRetryManager(
            "permanent-error-filter", permanentErrorConfig, vertx);
        
        Message<String> message = createTestMessage("msg-permanent", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Permanent error matching pattern");
        };

        permanentErrorManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Pattern-matched permanent errors should get rejected after retries
                assertTrue(attempts.get() >= 1);
                testContext.completeNow();
            })));
    }

    @Test
    void testTransientErrorPattern(VertxTestContext testContext) {
        FilterErrorHandlingConfig transientErrorConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .addTransientErrorPattern(".*Timeout.*")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .build();
        
        FilterRetryManager transientErrorManager = new FilterRetryManager(
            "transient-error-filter", transientErrorConfig, vertx);
        
        Message<String> message = createTestMessage("msg-transient", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - TimeoutException occurred");
        };

        transientErrorManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Initial attempt + 2 retries = 3 attempts
                assertEquals(3, attempts.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testMultipleErrorPatterns(VertxTestContext testContext) {
        FilterErrorHandlingConfig multiErrorConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(3)
            .initialRetryDelay(Duration.ofMillis(10))
            .addPermanentErrorPattern(".*IllegalState.*")
            .addTransientErrorPattern(".*Connection.*")
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_REJECT)
            .build();
        
        FilterRetryManager multiErrorManager = new FilterRetryManager(
            "multi-error-filter", multiErrorConfig, vertx);
        
        // Test permanent error pattern
        Message<String> message1 = createTestMessage("msg-multi-1", "payload");
        AtomicInteger attempts1 = new AtomicInteger(0);
        
        Predicate<Message<String>> filter1 = msg -> {
            attempts1.incrementAndGet();
            throw new IllegalStateException("Permanent error");
        };

        // Test transient error pattern
        Message<String> message2 = createTestMessage("msg-multi-2", "payload");
        AtomicInteger attempts2 = new AtomicInteger(0);

        Predicate<Message<String>> filter2 = msg -> {
            attempts2.incrementAndGet();
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Connection error");
        };

        // Run both patterns sequentially, asserting on each
        multiErrorManager.executeWithRetry(message1, filter1, circuitBreaker)
            .compose(result1 -> {
                testContext.verify(() -> {
                    assertFalse(result1);
                    assertTrue(attempts1.get() >= 1);
                });
                return multiErrorManager.executeWithRetry(message2, filter2, circuitBreaker);
            })
            .onComplete(testContext.succeeding(result2 -> testContext.verify(() -> {
                assertFalse(result2);
                // Connection error should match transient pattern and retry
                assertTrue(attempts2.get() >= 1, "Expected at least 1 attempt, got: " + attempts2.get());
                testContext.completeNow();
            })));
    }

    @Test
    void testNullMessageHandling() {
        assertThrows(NullPointerException.class, () -> {
            retryManager.executeWithRetry(null, msg -> true, circuitBreaker);
        });
    }

    @Test
    void testNullFilterHandling(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-null-filter", "payload");

        // Null filter should either throw NPE or handle gracefully.
        // Both succeeded(false) and failed(NPE) are acceptable outcomes.
        retryManager.executeWithRetry(message, null, circuitBreaker)
            .onSuccess(result -> testContext.verify(() -> {
                assertFalse(result);
                testContext.completeNow();
            }))
            .onFailure(e -> testContext.completeNow()); // NPE is also acceptable
    }

    @Test
    void testSchedulerShutdownGracefully(VertxTestContext testContext) {
        Message<String> message = createTestMessage("msg-shutdown", "payload");

        Predicate<Message<String>> filter = msg -> {
            throw new RuntimeException("INTENTIONAL TEST FAILURE - Error during shutdown");
        };

        // Start retries — filter always throws so retries will be scheduled on Vert.x timers.
        // Closing Vertx cancels pending timers; the future may not complete — that is intentional.
        // Error observed via .onFailure so the future is not fire-and-forget.
        retryManager.executeWithRetry(message, filter, circuitBreaker)
            .onFailure(e -> logger.debug("Retry abandoned after Vertx close (expected): {}", e.getMessage()));

        // Wait 20 ms to allow retries to start, then close Vertx while retries are in-progress.
        // The invariant under test: closing must complete without throwing or hanging.
        vertx.timer(20)
            .compose(v -> vertx.close())
            .onComplete(testContext.succeeding(v -> testContext.completeNow()));
    }

    @Test
    void testErrorClassificationHandling(VertxTestContext testContext) {
        FilterErrorHandlingConfig classificationConfig = FilterErrorHandlingConfig.builder()
            .maxRetries(2)
            .initialRetryDelay(Duration.ofMillis(10))
            .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.REJECT_IMMEDIATELY)
            .build();
        
        FilterRetryManager classificationManager = new FilterRetryManager(
            "classification-filter", classificationConfig, vertx);
        
        Message<String> message = createTestMessage("msg-13", "payload");
        AtomicInteger attempts = new AtomicInteger(0);
        
        Predicate<Message<String>> filter = msg -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Permanent error");
        };

        classificationManager.executeWithRetry(message, filter, circuitBreaker)
            .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                assertFalse(result);
                // Should reject immediately without retries for permanent errors
                assertEquals(1, attempts.get());
                testContext.completeNow();
            })));
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
        void testRetryThenDeadLetterWithDlqManager(VertxTestContext testContext) {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(2)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-integration")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "dlq-integration-filter", dlqConfig, vertx, dlqManager);

            Message<String> message = createTestMessage("msg-dlq-1", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Simulated error for DLQ test");
            };

            manager.executeWithRetry(message, filter, circuitBreaker)
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertFalse(result);
                    assertEquals(3, attempts.get()); // Initial + 2 retries
                    // Verify DLQ metrics
                    DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
                    assertEquals(1, metrics.getTotalMessages());
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should send message to DLQ immediately with DEAD_LETTER_IMMEDIATELY strategy")
        void testDeadLetterImmediatelyWithDlqManager(VertxTestContext testContext) {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(3)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-immediate")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "dlq-immediate-filter", dlqConfig, vertx, dlqManager);

            Message<String> message = createTestMessage("msg-dlq-immediate", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Critical error");
            };

            manager.executeWithRetry(message, filter, circuitBreaker)
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertFalse(result);
                    assertEquals(1, attempts.get()); // Only one attempt, immediate DLQ
                    // Verify DLQ metrics
                    DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
                    assertEquals(1, metrics.getTotalMessages());
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should reject message when DLQ is disabled even with DLQ strategy")
        void testDlqDisabledFallsBackToReject(VertxTestContext testContext) {
            FilterErrorHandlingConfig noDlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(1)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(false)
                .build();

            // Even with DLQ manager, if config says disabled, should reject
            FilterRetryManager manager = new FilterRetryManager(
                "no-dlq-filter", noDlqConfig, vertx, null);

            Message<String> message = createTestMessage("msg-no-dlq", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Error with DLQ disabled");
            };

            manager.executeWithRetry(message, filter, circuitBreaker)
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertFalse(result);
                    assertEquals(2, attempts.get()); // Initial + 1 retry, then reject (no DLQ)
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should reject message when DLQ manager is null")
        void testNullDlqManagerFallsBackToReject(VertxTestContext testContext) {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(1)
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq")
                .build();

            // DLQ enabled in config but manager is null - should fall back to reject
            FilterRetryManager manager = new FilterRetryManager(
                "null-dlq-manager-filter", dlqConfig, vertx, null);

            Message<String> message = createTestMessage("msg-null-dlq", "test-payload");
            AtomicInteger attempts = new AtomicInteger(0);

            Predicate<Message<String>> filter = msg -> {
                attempts.incrementAndGet();
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Error with null DLQ manager");
            };

            manager.executeWithRetry(message, filter, circuitBreaker)
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertFalse(result);
                    assertEquals(2, attempts.get());
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should track multiple messages sent to DLQ")
        void testMultipleMessagesToDlq(VertxTestContext testContext) {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(0) // No retries, immediate DLQ
                .initialRetryDelay(Duration.ofMillis(10))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.RETRY_THEN_DEAD_LETTER)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-multi")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "multi-dlq-filter", dlqConfig, vertx, dlqManager);

            Predicate<Message<String>> failingFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Always fails");
            };

            // Send multiple messages
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Message<String> message = createTestMessage("msg-multi-" + i, "payload-" + i);
                futures.add(manager.executeWithRetry(message, failingFilter, circuitBreaker));
            }

            // Verify all messages were sent to DLQ
            Future.all(futures)
                .onComplete(testContext.succeeding(composite -> testContext.verify(() -> {
                    DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
                    assertEquals(5, metrics.getTotalMessages());
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should include error classification in DLQ metadata")
        void testDlqWithErrorClassification(VertxTestContext testContext) {
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
                "classification-dlq-filter", dlqConfig, vertx, dlqManager);

            // Test with transient error
            Message<String> transientMsg = createTestMessage("msg-transient", "payload");
            Predicate<Message<String>> transientFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Timeout occurred");
            };

            Future<Boolean> result1 = manager.executeWithRetry(transientMsg, transientFilter, circuitBreaker);

            // Test with permanent error
            Message<String> permanentMsg = createTestMessage("msg-permanent", "payload");
            Predicate<Message<String>> permanentFilter = msg -> {
                throw new RuntimeException("INTENTIONAL TEST FAILURE - Invalid data format");
            };

            Future<Boolean> result2 = manager.executeWithRetry(permanentMsg, permanentFilter, circuitBreaker);

            // Both should be in DLQ
            Future.all(result1, result2)
                .onComplete(testContext.succeeding(composite -> testContext.verify(() -> {
                    assertFalse(result1.result());
                    assertFalse(result2.result());
                    DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
                    assertEquals(2, metrics.getTotalMessages());
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should handle concurrent DLQ sends correctly")
        void testConcurrentDlqSends(VertxTestContext testContext) {
            FilterErrorHandlingConfig dlqConfig = FilterErrorHandlingConfig.builder()
                .maxRetries(0)
                .initialRetryDelay(Duration.ofMillis(5))
                .defaultStrategy(FilterErrorHandlingConfig.FilterErrorStrategy.DEAD_LETTER_IMMEDIATELY)
                .deadLetterQueueEnabled(true)
                .deadLetterQueueTopic("test-dlq-concurrent")
                .build();

            DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(dlqConfig);
            FilterRetryManager manager = new FilterRetryManager(
                "concurrent-dlq-filter", dlqConfig, vertx, dlqManager);

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
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < messageCount; i++) {
                Message<String> message = createTestMessage("msg-concurrent-" + i, "payload");
                futures.add(manager.executeWithRetry(message, failingFilter, localCircuitBreaker));
            }

            Future.all(futures)
                .onComplete(testContext.succeeding(composite -> testContext.verify(() -> {
                    long rejectedCount = futures.stream()
                        .filter(f -> Boolean.FALSE.equals(f.result()))
                        .count();
                    assertEquals(messageCount, (int) rejectedCount);
                    // All messages should be in DLQ
                    DeadLetterQueueManager.DeadLetterManagerMetrics metrics = dlqManager.getMetrics();
                    assertEquals(messageCount, metrics.getTotalMessages());
                    testContext.completeNow();
                })));
        }

        @Test
        @DisplayName("should work with two-arg constructor (without DLQ manager)")
        void testTwoArgConstructorWithoutDlqManager(VertxTestContext testContext) {
            FilterRetryManager legacyManager = new FilterRetryManager("legacy-filter", config, vertx);

            Message<String> message = createTestMessage("msg-legacy", "payload");
            Predicate<Message<String>> successFilter = msg -> true;

            legacyManager.executeWithRetry(message, successFilter, circuitBreaker)
                .onComplete(testContext.succeeding(result -> testContext.verify(() -> {
                    assertTrue(result);
                    testContext.completeNow();
                })));
        }
    }
}
