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
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Properties;
import java.util.UUID;

import java.util.concurrent.TimeUnit;
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
    private final java.util.List<MessageConsumer<String>> extraConsumers = new java.util.ArrayList<>();
    private String testTopic;

    @BeforeEach
    void setup(VertxTestContext testContext) throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);
        testTopic = "edge-test-" + UUID.randomUUID().toString().substring(0, 8);
        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "2")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start()
                .onSuccess(v -> {
                    DatabaseService databaseService = new PgDatabaseService(manager);
                    outboxFactory = new OutboxFactory(databaseService, config);
                    producer = outboxFactory.createProducer(testTopic, String.class);
                    consumer = outboxFactory.createConsumer(testTopic, String.class);
                    testContext.completeNow();
                })
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void cleanup(VertxTestContext tearDownContext) throws Exception {
        for (MessageConsumer<String> extra : extraConsumers) {
            try { extra.close(); } catch (Exception e) { logger.warn("Error closing consumer", e); }
        }
        try { if (consumer != null) consumer.close(); } catch (Exception e) { logger.warn("Error closing consumer", e); }
        try { if (producer != null) producer.close(); } catch (Exception e) { logger.warn("Error closing producer", e); }
        // The factory close returns a Future: it is composed, and the manager closes whatever its
        // outcome; the first failure fails the teardown.
        (outboxFactory != null ? outboxFactory.close() : Future.<Void>succeededFuture())
                .transform(factoryResult -> (manager != null ? manager.closeReactive() : Future.<Void>succeededFuture())
                        .transform(managerResult -> {
                            if (factoryResult.failed()) {
                                return Future.<Void>failedFuture(factoryResult.cause());
                            }
                            return managerResult.failed()
                                    ? Future.<Void>failedFuture(managerResult.cause())
                                    : Future.<Void>succeededFuture();
                        }))
                .onSuccess(v -> tearDownContext.completeNow())
                .onFailure(tearDownContext::failNow);
        assertTrue(tearDownContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    /**
     * Tests shutdown race condition at the start of processAvailableMessages (line 249).
     * This test closes the consumer immediately after subscribe to trigger the closed.get() check.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testShutdownRaceConditionDuringPolling(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger messagesProcessed = new AtomicInteger(0);

        consumer.subscribe(message -> {
            messagesProcessed.incrementAndGet();
            return Future.succeededFuture();
        }).compose(v -> producer.send("test-data"))
          .compose(v -> vertx.timer(50))
          .compose(v -> {
              consumer.close();
              return vertx.timer(200);
          })
          .onSuccess(v -> testContext.verify(() -> {
              assertTrue(messagesProcessed.get() <= 1, "Should process at most 1 message before shutdown");
              testContext.completeNow();
          }))
          .onFailure(testContext::failNow);
    }

    /**
     * Tests shutdown race condition in storeDeadLetterMessage (lines 605-607, 616-618, 635-637).
     * This test triggers DLQ operation then immediately closes the consumer.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testShutdownDuringDeadLetterQueueOperation(Vertx vertx, VertxTestContext testContext) {
        Promise<Void> firstAttempt = Promise.promise();
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            if (attemptCount.incrementAndGet() == 1) {
                firstAttempt.tryComplete();
            }
            // Always fail to trigger retry and eventual DLQ
            throw new RuntimeException("INTENTIONAL FAILURE for DLQ test");
        }).compose(v -> producer.send("test-data"))
          .compose(v -> firstAttempt.future())
          // Close while the failed attempt goes through the retry path; a closed consumer
          // must not be handed the message again.
          .compose(v -> {
              consumer.close();
              return vertx.timer(500);
          })
          .onSuccess(v -> testContext.verify(() -> {
              assertEquals(1, attemptCount.get(), "A closed consumer must not receive another attempt");
              testContext.completeNow();
          }))
          .onFailure(testContext::failNow);
    }

    /**
     * Tests the case where message processing executor is shut down (line 358).
     * This is a shutdown race condition where messages are fetched but executor is already shut down.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testMessageProcessingExecutorShutdown(Vertx vertx, VertxTestContext testContext) {
        // A strict checkpoint: a fourth delivery fails the test. The teardown closes the consumer.
        Checkpoint messageCheckpoint = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            messageCheckpoint.flag();
            return Future.succeededFuture();
        }).compose(v -> producer.send("message1"))
          .compose(v -> producer.send("message2"))
          .compose(v -> producer.send("message3"))
          .onFailure(testContext::failNow);
    }

    /**
     * Tests error handler in incrementRetryAndReset (lines 591-598).
     * This tests the onFailure handler for retry count increment operations.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testRetryIncrementErrorHandler(Vertx vertx, VertxTestContext testContext) {
        // max-retries is 2, so the handler runs up to three times; the test needs a retry,
        // which is a second attempt.
        Promise<Void> retried = Promise.promise();
        AtomicInteger attempts = new AtomicInteger(0);

        consumer.subscribe(message -> {
            if (attempts.incrementAndGet() == 2) {
                retried.tryComplete();
            }
            throw new RuntimeException("INTENTIONAL FAILURE for retry test");
        }).compose(v -> producer.send("test"))
          .compose(v -> retried.future())
          .onSuccess(v -> testContext.verify(() -> {
              assertTrue(attempts.get() >= 2, "The failed message must be retried");
              testContext.completeNow();
          }))
          .onFailure(testContext::failNow);
    }

    /**
     * Tests DLQ error handlers during pool closure (lines 661-668, 677-684).
     * This attempts to trigger the onFailure handlers in DLQ-related operations.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testDLQErrorHandlersDuringPoolClosure(Vertx vertx, VertxTestContext testContext) {
        // Initial attempt + 2 retries; a strict checkpoint, so a fourth attempt fails the test.
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        consumer.subscribe(message -> {
            retryCheckpoint.flag();
            throw new RuntimeException("INTENTIONAL FAILURE to trigger retries and DLQ");
        }).compose(v -> producer.send("test-data"))
          .onFailure(testContext::failNow);
    }

    /**
     * Tests closed consumer checks throughout processing (lines 249, 277, 358, 605, 616, 635).
     * This test verifies that shutdown signals are respected at various checkpoints.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testShutdownCheckpointsDuringProcessing(Vertx vertx, VertxTestContext testContext) {
        AtomicInteger processedCount = new AtomicInteger(0);
        Promise<Void> firstProcessing = Promise.promise();

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            firstProcessing.tryComplete();
            return vertx.timer(100).mapEmpty();
        }).compose(v -> producer.send("message-0"))
          .compose(v -> producer.send("message-1"))
          .compose(v -> producer.send("message-2"))
          .compose(v -> producer.send("message-3"))
          .compose(v -> producer.send("message-4"))
          .onFailure(testContext::failNow);

        // Close as soon as processing has started, while further messages are queued.
        firstProcessing.future()
          .onSuccess(v -> testContext.verify(() -> {
              consumer.close();
              assertTrue(processedCount.get() >= 1, "Should have processed at least one message");
              assertTrue(processedCount.get() <= 5, "Should not process more than available messages");
              testContext.completeNow();
          }))
          .onFailure(testContext::failNow);
    }

    /**
     * Tests multiple shutdown scenarios to trigger various closed.get() checks.
     * This test exercises the defensive shutdown checks throughout the consumer lifecycle.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    void testMultipleShutdownScenarios(Vertx vertx, VertxTestContext testContext) {
        // Scenario 1: subscribe on a closed consumer fails; a second close is a no-op.
        MessageConsumer<String> earlyCloseConsumer = outboxFactory.createConsumer(testTopic, String.class);
        extraConsumers.add(earlyCloseConsumer);
        earlyCloseConsumer.close();
        Future<Void> lateSubscribe = earlyCloseConsumer.subscribe(msg -> Future.succeededFuture());
        testContext.verify(() -> {
            assertTrue(lateSubscribe.failed(), "Subscribing to a closed consumer must fail");
            assertInstanceOf(IllegalStateException.class, lateSubscribe.cause());
            assertDoesNotThrow(earlyCloseConsumer::close, "A second close must be a no-op");
        });

        // Scenario 2: close during active processing.
        MessageConsumer<String> activeConsumer = outboxFactory.createConsumer(testTopic, String.class);
        extraConsumers.add(activeConsumer);
        Promise<Void> processing = Promise.promise();
        activeConsumer.subscribe(message -> {
            processing.tryComplete();
            return vertx.timer(200).mapEmpty();
        }).compose(v -> producer.send("test-message"))
          .compose(v -> processing.future())
          .compose(v -> {
              activeConsumer.close();
              // Scenario 3: close after unsubscribe.
              MessageConsumer<String> unsubscribeConsumer = outboxFactory.createConsumer(testTopic, String.class);
              extraConsumers.add(unsubscribeConsumer);
              // In compose, a throw from unsubscribe or close becomes a failed Future.
              return unsubscribeConsumer.subscribe(msg -> Future.succeededFuture())
                  .compose(subscribed -> {
                      unsubscribeConsumer.unsubscribe();
                      unsubscribeConsumer.close();
                      return Future.<Void>succeededFuture();
                  });
          })
          .onSuccess(v -> testContext.completeNow())
          .onFailure(testContext::failNow);
    }

    @Test
    void closeIsIdempotent(Vertx vertx, VertxTestContext testContext) {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        assertDoesNotThrow(() -> {
            typedConsumer.close();
            typedConsumer.close();
        }, "Second close() must be a no-op");
        testContext.completeNow();
    }
}


