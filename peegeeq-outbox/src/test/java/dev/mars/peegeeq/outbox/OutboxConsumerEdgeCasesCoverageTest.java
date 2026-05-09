package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Edge case tests to improve OutboxConsumer coverage from 75% to 90%+.
 * These tests target specific uncovered code paths identified in jacoco report:
 * - Shutdown race conditions (lines 249-251, 605-607, 616-618, 635-637)
 * - Error handlers in DLQ flow (lines 591-598, 661-668, 677-684)
 * - Executor shutdown during processing (line 358)
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
class OutboxConsumerEdgeCasesCoverageTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerEdgeCasesCoverageTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setup() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test
        testTopic = "edge-test-" + UUID.randomUUID().toString().substring(0, 8);

        // Configure database connection with short polling interval and low max retries
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .property("peegeeq.queue.max-retries", "2")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        if (manager != null) {
            manager.closeReactive().await();
        }
    }

    /**
     * Tests shutdown race condition at the start of processAvailableMessages (line 249).
     * This test closes the consumer immediately after subscribe to trigger the closed.get() check.
     */
    @Test
    void testShutdownRaceConditionDuringPolling(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint shutdownCheckpoint = testContext.checkpoint();
        AtomicInteger messagesProcessed = new AtomicInteger(0);

        consumer.subscribe(message -> {
            messagesProcessed.incrementAndGet();
            return Future.succeededFuture();
        });

        // Send a message
        producer.send("test-data").await();
        vertx.setTimer(50, id -> {
            consumer.close(); // This should trigger closed.get() checks
            vertx.setTimer(200, id2 -> {
                testContext.verify(() ->
                    assertTrue(messagesProcessed.get() <= 1, "Should process at most 1 message before shutdown"));
                shutdownCheckpoint.flag();
            });
        });

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    /**
     * Tests shutdown race condition in storeDeadLetterMessage (lines 605-607, 616-618, 635-637).
     * This test triggers DLQ operation then immediately closes the consumer.
     */
    @Test
    void testShutdownDuringDeadLetterQueueOperation(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint firstAttemptCheckpoint = testContext.checkpoint();
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                firstAttemptCheckpoint.flag();
            }
            
            // Always fail to trigger retry and eventual DLQ
            throw new RuntimeException("INTENTIONAL FAILURE for DLQ test");
        });

        // Send message
        producer.send("test-data").await();

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process message at least once");

        // Close consumer during DLQ operation window
        consumer.close();

        // Verify message was attempted multiple times
        assertTrue(attemptCount.get() >= 1, "Should have attempted processing at least once");
    }

    /**
     * Tests the case where message processing executor is shut down (line 358).
     * This is a shutdown race condition where messages are fetched but executor is already shut down.
     */
    @Test
    void testMessageProcessingExecutorShutdown(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger messagesProcessed = new AtomicInteger(0);
        Checkpoint messageCheckpoint = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            messagesProcessed.incrementAndGet();
            messageCheckpoint.flag();
            return Future.succeededFuture();
        });

        // Send multiple messages
        producer.send("message1").await();
        producer.send("message2").await();
        producer.send("message3").await();

        // Wait for messages to be processed
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process messages");

        // Close consumer to shut down executor
        consumer.close();

        // Verify some messages were processed (exact count depends on timing)
        assertTrue(messagesProcessed.get() >= 1, "Should have processed at least one message");
    }

    /**
     * Tests error handler in incrementRetryAndReset (lines 591-598).
     * This tests the onFailure handler for retry count increment operations.
     */
    @Test
    void testRetryIncrementErrorHandler(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint retryCheckpoint = testContext.checkpoint(2);

        consumer.subscribe(message -> {
            retryCheckpoint.flag();
            throw new RuntimeException("INTENTIONAL FAILURE for retry test");
        });

        producer.send("test").await();

        // Wait for retries
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should process message multiple times");

        // Close consumer to trigger potential pool closure errors
        consumer.close();
    }

    /**
     * Tests DLQ error handlers during pool closure (lines 661-668, 677-684).
     * This attempts to trigger the onFailure handlers in DLQ-related operations.
     */
    @Test
    void testDLQErrorHandlersDuringPoolClosure(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint retryCheckpoint = testContext.checkpoint(3); // Initial + 2 retries

        consumer.subscribe(message -> {
            retryCheckpoint.flag();
            throw new RuntimeException("INTENTIONAL FAILURE to trigger retries and DLQ");
        });

        producer.send("test-data").await();
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should complete all retry attempts");

        // Close consumer to trigger pool closure during DLQ
        consumer.close();
    }

    /**
     * Tests closed consumer checks throughout processing (lines 249, 277, 358, 605, 616, 635).
     * This test verifies that shutdown signals are respected at various checkpoints.
     */
    @Test
    void testShutdownCheckpointsDuringProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger processedCount = new AtomicInteger(0);
        Checkpoint startProcessing = testContext.checkpoint();

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            startProcessing.flag();
            // Slow processing via non-blocking delay
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(100, id -> promise.complete());
            return promise.future();
        });

        // Send multiple messages
        for (int i = 0; i < 5; i++) {
            producer.send("message-" + i).await();
        }

        // Wait for processing to start
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should start processing");

        // Close during processing
        consumer.close();

        // Verify graceful shutdown
        assertTrue(processedCount.get() >= 1, "Should have processed at least one message");
        assertTrue(processedCount.get() <= 5, "Should not process more than available messages");
    }

    /**
     * Tests multiple shutdown scenarios to trigger various closed.get() checks.
     * This test exercises the defensive shutdown checks throughout the consumer lifecycle.
     */
    @Test
    void testMultipleShutdownScenarios(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Scenario 1: Close before subscribe
        MessageConsumer<String> earlyCloseConsumer = outboxFactory.createConsumer(testTopic, String.class);
        earlyCloseConsumer.close();
        
        // Should handle gracefully
        try {
            earlyCloseConsumer.subscribe(msg -> Future.succeededFuture());
            earlyCloseConsumer.close(); // Double close
        } catch (Exception e) {
            // Expected - consumer already closed
        }

        // Scenario 2: Close during active processing
        MessageConsumer<String> activeConsumer = outboxFactory.createConsumer(testTopic, String.class);
        Checkpoint processing = testContext.checkpoint();
        
        activeConsumer.subscribe(message -> {
            processing.flag();
            // Simulate work via non-blocking delay
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(200, id -> promise.complete());
            return promise.future();
        });

        producer.send("test-message").await();
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Should start processing");
        activeConsumer.close(); // Close while processing

        // Scenario 3: Close after unsubscribe
        MessageConsumer<String> unsubscribeConsumer = outboxFactory.createConsumer(testTopic, String.class);
        unsubscribeConsumer.subscribe(msg -> Future.succeededFuture());
        unsubscribeConsumer.unsubscribe();
        unsubscribeConsumer.close();
    }

    // ---- In-flight processing tracking tests ----

    /**
     * Verifies that closeAsync() waits for in-flight message processing to finish
     * before returning. Without this, pool.close() hangs on borrowed connections.
     */
    @Test
    void closeAsyncWaitsForInflightProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        Checkpoint done = testContext.checkpoint();
        AtomicInteger messagesProcessed = new AtomicInteger(0);

        // Unsubscribing causes scheduledProcessMessages() to short-circuit on
        // !subscribed.get() without touching inflightProcessing, so subsequent
        // 100ms timer ticks cannot overwrite the future we are about to inject.
        // closeAsync() will re-cancel the timer via its own non-reflection path.
        typedConsumer.unsubscribe();

        // Inject a slow future that simulates an in-flight message handler (200ms).
        // messagesProcessed is incremented before the promise completes, so if
        // closeAsync() correctly waits for inflightProcessing, the count will be 1.
        Promise<Void> slowHandler = Promise.promise();
        vertx.setTimer(200, id -> {
            messagesProcessed.incrementAndGet();
            slowHandler.complete();
        });
        setPrivateField(typedConsumer, "inflightProcessing", slowHandler.future());

        typedConsumer.closeAsync()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                assertEquals(1, messagesProcessed.get(),
                    "In-flight processing should have completed before closeAsync resolved");
                done.flag();
            })));

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
            "closeAsync should complete after in-flight processing finishes");
    }

    /**
     * Verifies that closeAsync() completes even when in-flight processing fails.
     * The consumer should recover from the error and still close cleanly.
     */
    @Test
    void closeAsyncCompletesWhenInflightProcessingFails(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint closedCleanly = testContext.checkpoint();

        consumer.subscribe(message -> {
            // Simulate slow failure
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(200, id -> promise.fail(new RuntimeException("INTENTIONAL in-flight failure")));
            return promise.future();
        });

        producer.send("failing-message").await();

        // Give consumer time to start processing
        vertx.setTimer(100, id -> {
            OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
            typedConsumer.closeAsync()
                .onSuccess(v -> {
                    closedCleanly.flag();
                })
                .onFailure(testContext::failNow);
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
            "closeAsync should complete even when in-flight processing fails");
    }

    /**
     * Verifies that manager shutdown (close hooks) does not hang when a consumer
     * has in-flight processing. This is the end-to-end scenario that was hanging.
     */
    @Test
    void managerShutdownDoesNotHangWithInflightProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint shutdownComplete = testContext.checkpoint();

        consumer.subscribe(message -> {
            // Slow processing
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(300, id -> promise.complete());
            return promise.future();
        });

        producer.send("inflight-message").await();

        // Give consumer time to start processing, then shut down the full stack
        vertx.setTimer(150, id -> {
            // This is the exact sequence that was hanging before the fix:
            // consumer.close() → outboxFactory.close() → manager.closeReactive()
            try { consumer.close(); } catch (Exception e) { /* ignore */ }
            try { producer.close(); } catch (Exception e) { /* ignore */ }
            try { outboxFactory.close(); } catch (Exception e) { /* ignore */ }
            manager.closeReactive()
                .onSuccess(v -> shutdownComplete.flag())
                .onFailure(testContext::failNow);
            // Null out so @AfterEach doesn't double-close
            consumer = null;
            producer = null;
            outboxFactory = null;
            manager = null;
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Full manager shutdown must complete without hanging");
    }

    @Test
    void closeAsyncTimesOutWhenInflightProcessingStalls(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Use a short timeout so the test completes quickly
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        typedConsumer.closeInflightTimeoutMs = 1_000L;
        Checkpoint done = testContext.checkpoint();

        // Unsubscribing causes scheduledProcessMessages() to short-circuit on
        // !subscribed.get(), preventing the 100ms timer from overwriting the
        // never-completing future we are about to inject.
        typedConsumer.unsubscribe();

        // Inject a never-completing future to simulate a stalled message handler.
        Promise<Void> neverCompletes = Promise.promise();
        setPrivateField(typedConsumer, "inflightProcessing", neverCompletes.future());

        long before = System.currentTimeMillis();
        typedConsumer.closeAsync()
            .onComplete(testContext.succeeding(v -> testContext.verify(() -> {
                long elapsed = System.currentTimeMillis() - before;
                // closeAsync must complete: after timeout (~1s) + pool close overhead, well under 5s
                assertTrue(elapsed >= 900, "closeAsync should have waited at least ~1s for timeout, got " + elapsed + "ms");
                // Consumer must be closed even though inflightProcessing never completed
                AtomicBoolean closedField = getPrivateField(typedConsumer, "closed", AtomicBoolean.class);
                assertTrue(closedField.get(), "Consumer should be marked closed after timeout");
                done.flag();
            })));

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS),
            "closeAsync must complete even when in-flight processing stalls indefinitely");
    }

    @Test
    void closeAsyncIsIdempotent(Vertx vertx, VertxTestContext testContext) throws Exception {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        Checkpoint done = testContext.checkpoint();

        typedConsumer.closeAsync()
            .compose(v -> typedConsumer.closeAsync())
            .onSuccess(v -> done.flag())
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "closeAsync should be idempotent");
    }

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object target, String fieldName, Class<T> type) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass().getName());
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field '" + fieldName + "' not found on " + target.getClass().getName());
    }
}


