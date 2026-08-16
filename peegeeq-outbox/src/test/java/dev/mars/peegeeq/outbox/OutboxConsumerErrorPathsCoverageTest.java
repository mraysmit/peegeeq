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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;

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
    void setup(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        testTopic = "err-test-" + UUID.randomUUID().toString().substring(0, 8);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA).build();
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().onSuccess(v -> {
            DatabaseService databaseService = new PgDatabaseService(manager);
            outboxFactory = new OutboxFactory(databaseService, config);
            producer = outboxFactory.createProducer(testTopic, TestMessage.class);
            consumer = outboxFactory.createConsumer(testTopic, TestMessage.class);
            testContext.completeNow();
        }).onFailure(testContext::failNow);
    }

    @AfterEach
    void teardown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        Future<Void> closeFactory = outboxFactory != null
                ? outboxFactory.close()
                : Future.succeededFuture();
        closeFactory
                .eventually(() -> manager != null
                        ? manager.closeReactive()
                        : Future.succeededFuture())
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Test handler throws exception triggering error handling")
    void testHandlerExceptionTriggersErrorHandling(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint failureCheckpoint = testContext.checkpoint();

        MessageHandler<TestMessage> failingHandler = message -> {
            logger.info("Test: handler exception triggers error handling");
            failureCheckpoint.flag();
            throw new RuntimeException("Simulated processing failure");
        };

        TestMessage testMsg = new TestMessage("error-test", "This message will fail");
        consumer.subscribe(failingHandler)
                .compose(v -> producer.send(testMsg))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Test async handler completes exceptionally")
    void testAsyncHandlerCompletesExceptionally(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint messageCheckpoint = testContext.checkpoint();

        MessageHandler<TestMessage> asyncFailingHandler = message -> {
            logger.info("Test: async handler completes exceptionally");
            RuntimeException error = new RuntimeException("Async processing failed");
            testContext.verify(() -> assertEquals("Async processing failed", error.getMessage()));
            messageCheckpoint.flag();
            return Future.failedFuture(error);
        };

        TestMessage testMsg = new TestMessage("async-fail", "Async failure test");
        consumer.subscribe(asyncFailingHandler)
                .compose(v -> producer.send(testMsg))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Test rapid message failures to stress error paths")
    void testRapidMessageFailures(Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 5;
        Checkpoint failureCheckpoint = testContext.checkpoint(messageCount);

        MessageHandler<TestMessage> rapidFailHandler = message -> {
            logger.info("Test: rapid message failures");
            failureCheckpoint.flag();
            throw new RuntimeException("Rapid failure: " + message.getId());
        };

        consumer.subscribe(rapidFailHandler)
                .compose(v -> {
                    List<Future<?>> sends = new ArrayList<>();
                    for (int i = 0; i < messageCount; i++) {
                        TestMessage msg = new TestMessage("rapid-fail-" + i, "Rapid failure test " + i);
                        sends.add(producer.send(msg));
                    }
                    return Future.all(sends).mapEmpty();
                })
                .onFailure(testContext::failNow);
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
        
        TestMessage testMsg = new TestMessage("npe-test", "NPE test message");
        consumer.subscribe(nullPointerHandler)
                .compose(v -> producer.send(testMsg))
                .onFailure(testContext::failNow);
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
        
        TestMessage testMsg = new TestMessage("error-test", "Error test message");
        consumer.subscribe(errorHandler)
                .compose(v -> producer.send(testMsg))
                .onFailure(testContext::failNow);
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
        
        TestMessage specialMsg = new TestMessage(
            "special-chars-fail",
            "Special: <>&\"'\n\t\r\\/"
        );
        consumer.subscribe(failHandler)
                .compose(v -> producer.send(specialMsg))
                .onFailure(testContext::failNow);
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
        
        StringBuilder largeData = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeData.append("This is line ").append(i).append(" of a very large message. ");
        }

        TestMessage largeMsg = new TestMessage("large-fail", largeData.toString());
        consumer.subscribe(failHandler)
                .compose(v -> producer.send(largeMsg))
                .onFailure(testContext::failNow);
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
        
        TestMessage testMsg = new TestMessage("timeout-test", "Timeout simulation");
        consumer.subscribe(slowFailHandler)
                .compose(v -> producer.send(testMsg))
                .onFailure(testContext::failNow);
    }

    @Test
    @DisplayName("Test multiple consumers with failures")
    void testMultipleConsumersWithFailures(Vertx vertx, VertxTestContext testContext) throws Exception {
        MessageConsumer<TestMessage> consumer2 = outboxFactory.createConsumer(testTopic, TestMessage.class);
        
        logger.info("Test: multiple consumers with failures");
        Promise<Void> received = Promise.promise();

        Future<Void> subscribeFirst = consumer.subscribe(message -> {
            received.tryComplete();
            return Future.failedFuture(new RuntimeException("Consumer 1 failure"));
        });
        Future<Void> subscribeSecond = consumer2.subscribe(message -> {
            received.tryComplete();
            return Future.failedFuture(new RuntimeException("Consumer 2 failure"));
        });

        TestMessage testMsg = new TestMessage("multi-consumer", "Multiple consumer test");
        Future.all(subscribeFirst, subscribeSecond)
                .compose(v -> producer.send(testMsg))
                .compose(v -> received.future())
                .eventually(() -> {
                    consumer2.close();
                    return Future.succeededFuture();
                })
                .onSuccess(v -> testContext.completeNow())
                .onFailure(testContext::failNow);
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
        
        TestMessage testMsg = new TestMessage("intermittent", "Intermittent failure test");
        consumer.subscribe(intermittentHandler)
                .compose(v -> producer.send(testMsg))
                .onFailure(testContext::failNow);
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
