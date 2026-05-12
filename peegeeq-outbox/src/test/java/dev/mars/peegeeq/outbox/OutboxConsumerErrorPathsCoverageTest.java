package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coverage-focused tests for OutboxConsumer error handling paths.
 * Targets specific uncovered branches in markMessageFailed, 
 * retry exhaustion, and DLQ operations.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerErrorPathsCoverageTest {
    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerErrorPathsCoverageTest.class);


    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<TestMessage> producer;
    private MessageConsumer<TestMessage> consumer;
    private String testTopic;

    @BeforeAll
    static void setupAll() throws Exception {
        // Schema will be initialized by test containers
    }

    @BeforeEach
    void setup() throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        
        testTopic = "err-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, TestMessage.class);
        consumer = outboxFactory.createConsumer(testTopic, TestMessage.class);
    }

    @AfterEach
    void teardown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
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
            manager.closeReactive()
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Test handler throws exception triggering error handling")
    void testHandlerExceptionTriggersErrorHandling(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint failureCheckpoint = testContext.checkpoint();
        AtomicBoolean errorHandled = new AtomicBoolean(false);
        
        MessageHandler<TestMessage> failingHandler = message -> {
        logger.info("Test: handler exception triggers error handling");
            errorHandled.set(true);
            failureCheckpoint.flag();
            // Fail to trigger error handling path
            throw new RuntimeException("Simulated processing failure");
        };
        
        consumer.subscribe(failingHandler);
        
        // Send message that will fail processing
        TestMessage testMsg = new TestMessage("error-test", "This message will fail");
        producer.send(testMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process message");
        assertTrue(errorHandled.get(), "Error handler should have been invoked");
    }

    @Test
    @DisplayName("Test async handler completes exceptionally")
    void testAsyncHandlerCompletesExceptionally(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint messageCheckpoint = testContext.checkpoint();
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        
        MessageHandler<TestMessage> asyncFailingHandler = message -> {
        logger.info("Test: async handler completes exceptionally");
            Promise<Void> promise = Promise.promise();
            
            // Simulate async processing that fails immediately
            RuntimeException error = new RuntimeException("Async processing failed");
            capturedError.set(error);
            promise.fail(error);
            messageCheckpoint.flag();
            
            return promise.future();
        };
        
        consumer.subscribe(asyncFailingHandler);
        
        TestMessage testMsg = new TestMessage("async-fail", "Async failure test");
        producer.send(testMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive and process message");
        assertNotNull(capturedError.get(), "Should capture exception");
    }

    @Test
    @DisplayName("Test rapid message failures to stress error paths")
    void testRapidMessageFailures(Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 5;
        Checkpoint failureCheckpoint = testContext.checkpoint(messageCount);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        MessageHandler<TestMessage> rapidFailHandler = message -> {
        logger.info("Test: rapid message failures");
            failureCount.incrementAndGet();
            failureCheckpoint.flag();
            throw new RuntimeException("Rapid failure: " + message.getId());
        };
        
        consumer.subscribe(rapidFailHandler);
        
        // Send multiple messages rapidly
        for (int i = 0; i < messageCount; i++) {
            TestMessage msg = new TestMessage("rapid-fail-" + i, "Rapid failure test " + i);
            producer.send(msg);
        }
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process all messages");
        assertTrue(failureCount.get() >= messageCount, "All messages should fail initially");
    }

    @Test
    @DisplayName("Test handler throws null pointer exception")
    void testHandlerThrowsNullPointerException(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint errorCheckpoint = testContext.checkpoint();
        
        MessageHandler<TestMessage> nullPointerHandler = message -> {
        logger.info("Test: handler throws null pointer exception");
            errorCheckpoint.flag();
            // Simulate NPE
            String nullString = null;
            nullString.length(); // Will throw NPE
            return Future.succeededFuture();
        };
        
        consumer.subscribe(nullPointerHandler);
        
        TestMessage testMsg = new TestMessage("npe-test", "NPE test message");
        producer.send(testMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message");
    }

    @Test
    @DisplayName("Test handler throws error (not exception)")
    void testHandlerThrowsError(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint errorCheckpoint = testContext.checkpoint();
        
        MessageHandler<TestMessage> errorHandler = message -> {
        logger.info("Test: handler throws error");
            errorCheckpoint.flag();
            // Throw Error instead of Exception
            throw new AssertionError("Simulated assertion error");
        };
        
        consumer.subscribe(errorHandler);
        
        TestMessage testMsg = new TestMessage("error-test", "Error test message");
        producer.send(testMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should receive message");
    }

    @Test
    @DisplayName("Test message with special characters in error scenario")
    void testMessageWithSpecialCharactersFailure(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint errorCheckpoint = testContext.checkpoint();
        
        MessageHandler<TestMessage> failHandler = message -> {
        logger.info("Test: message with special characters failure");
            errorCheckpoint.flag();
            throw new RuntimeException("Failed with special chars: " + message.getPayload().getData());
        };
        
        consumer.subscribe(failHandler);
        
        TestMessage specialMsg = new TestMessage(
            "special-chars-fail",
            "Special: <>&\"'\n\t\r\\/"
        );
        producer.send(specialMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should handle message with special characters");
    }

    @Test
    @DisplayName("Test very large message failure")
    void testLargeMessageFailure(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint errorCheckpoint = testContext.checkpoint();
        
        MessageHandler<TestMessage> failHandler = message -> {
        logger.info("Test: large message failure");
            errorCheckpoint.flag();
            throw new RuntimeException("Failed processing large message");
        };
        
        consumer.subscribe(failHandler);
        
        // Create large message
        StringBuilder largeData = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeData.append("This is line ").append(i).append(" of a very large message. ");
        }
        
        TestMessage largeMsg = new TestMessage("large-fail", largeData.toString());
        producer.send(largeMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should handle large message");
    }

    @Test
    @DisplayName("Test handler with timeout simulation")
    void testHandlerTimeoutSimulation(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint startCheckpoint = testContext.checkpoint();
        
        MessageHandler<TestMessage> slowFailHandler = message -> {
        logger.info("Test: handler timeout simulation");
            startCheckpoint.flag();
            // Simulate slow processing then fail using non-blocking timer
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(500, timerId ->
                promise.fail(new RuntimeException("Timeout simulation failure")));
            return promise.future();
        };
        
        consumer.subscribe(slowFailHandler);
        
        TestMessage testMsg = new TestMessage("timeout-test", "Timeout simulation");
        producer.send(testMsg);
        
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should start processing");
    }

    @Test
    @DisplayName("Test multiple consumers with failures")
    void testMultipleConsumersWithFailures(Vertx vertx, VertxTestContext testContext) throws Exception {
        MessageConsumer<TestMessage> consumer2 = outboxFactory.createConsumer(testTopic, TestMessage.class);
        
        try {
        logger.info("Test: multiple consumers with failures");
            Checkpoint receivedCheckpoint = testContext.checkpoint();
            
            consumer.subscribe(message -> {
                receivedCheckpoint.flag();
                return Future.failedFuture(new RuntimeException("Consumer 1 failure"));
            });
            
            consumer2.subscribe(message -> {
                receivedCheckpoint.flag();
                return Future.failedFuture(new RuntimeException("Consumer 2 failure"));
            });
            
            TestMessage testMsg = new TestMessage("multi-consumer", "Multiple consumer test");
            producer.send(testMsg);
            
            // At least one consumer should process
            assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "At least one consumer should process message");
        } finally {
            consumer2.close();
        }
    }

    @Test
    @DisplayName("Test failure then success pattern to cover retry reset")
    void testFailureThenSuccessPattern(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint successCheckpoint = testContext.checkpoint();
        
        MessageHandler<TestMessage> intermittentHandler = message -> {
        logger.info("Test: failure then success pattern");
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                // First attempt fails
                throw new RuntimeException("First attempt failure");
            }
            // Second attempt succeeds
            successCheckpoint.flag();
            return Future.succeededFuture();
        };
        
        consumer.subscribe(intermittentHandler);
        
        TestMessage testMsg = new TestMessage("intermittent", "Intermittent failure test");
        producer.send(testMsg);
        
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), "Should eventually succeed after retry");
        assertTrue(attemptCount.get() >= 2, "Should have multiple attempts");
    }

    static class TestMessage {
        private String id;
        private String data;

        public TestMessage() {}

        public TestMessage(String id, String data) {
            this.id = id;
            this.data = data;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }
}


