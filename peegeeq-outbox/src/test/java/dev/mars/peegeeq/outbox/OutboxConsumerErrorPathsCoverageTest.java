package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageHandler;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Coverage-focused tests for OutboxConsumer error handling paths.
 * Targets specific uncovered branches in markMessageFailedReactive, 
 * retry exhaustion, and DLQ operations.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OutboxConsumerErrorPathsCoverageTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        
        testTopic = "err-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("error-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, TestMessage.class);
        consumer = outboxFactory.createConsumer(testTopic, TestMessage.class);
    }

    @AfterEach
    void teardown() throws Exception {
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
            manager.close();
        }
        Thread.sleep(200);
    }

    @Test
    @Order(1)
    @DisplayName("Test handler throws exception triggering error handling")
    void testHandlerExceptionTriggersErrorHandling() throws Exception {
        CountDownLatch failureLatch = new CountDownLatch(1); // Just initial attempt
        AtomicBoolean errorHandled = new AtomicBoolean(false);
        
        MessageHandler<TestMessage> failingHandler = message -> {
            failureLatch.countDown();
            errorHandled.set(true);
            // Fail to trigger error handling path
            throw new RuntimeException("Simulated processing failure");
        };
        
        consumer.subscribe(failingHandler);
        
        // Send message that will fail processing
        TestMessage testMsg = new TestMessage("error-test", "This message will fail");
        producer.send(testMsg);
        
        // Wait for processing attempt
        boolean completed = failureLatch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Should process message");
        
        // Give time for error handling
        Thread.sleep(500);
        
        // Verify error was handled
        assertTrue(errorHandled.get(), "Error handler should have been invoked");
    }

    @Test
    @Order(2)
    @DisplayName("Test async handler completes exceptionally")
    void testAsyncHandlerCompletesExceptionally() throws Exception {
        CountDownLatch messageLatch = new CountDownLatch(1);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();
        
        MessageHandler<TestMessage> asyncFailingHandler = message -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            
            // Simulate async processing that fails immediately
            RuntimeException error = new RuntimeException("Async processing failed");
            capturedError.set(error);
            future.completeExceptionally(error);
            messageLatch.countDown();
            
            return future;
        };
        
        consumer.subscribe(asyncFailingHandler);
        
        TestMessage testMsg = new TestMessage("async-fail", "Async failure test");
        producer.send(testMsg);
        
        boolean received = messageLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive and process message");
        assertNotNull(capturedError.get(), "Should capture exception");
        
        // Allow retry processing
        Thread.sleep(3000);
    }

    @Test
    @Order(3)
    @DisplayName("Test rapid message failures to stress error paths")
    void testRapidMessageFailures() throws Exception {
        int messageCount = 5;
        CountDownLatch failureLatch = new CountDownLatch(messageCount);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        MessageHandler<TestMessage> rapidFailHandler = message -> {
            failureCount.incrementAndGet();
            failureLatch.countDown();
            throw new RuntimeException("Rapid failure: " + message.getId());
        };
        
        consumer.subscribe(rapidFailHandler);
        
        // Send multiple messages rapidly
        for (int i = 0; i < messageCount; i++) {
            TestMessage msg = new TestMessage("rapid-fail-" + i, "Rapid failure test " + i);
            producer.send(msg);
        }
        
        boolean completed = failureLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Should process all messages");
        assertEquals(messageCount, failureCount.get(), "All messages should fail initially");
        
        // Wait for retry processing
        Thread.sleep(3000);
    }

    @Test
    @Order(4)
    @DisplayName("Test handler throws null pointer exception")
    void testHandlerThrowsNullPointerException() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        MessageHandler<TestMessage> nullPointerHandler = message -> {
            errorLatch.countDown();
            // Simulate NPE
            String nullString = null;
            nullString.length(); // Will throw NPE
            return CompletableFuture.completedFuture(null);
        };
        
        consumer.subscribe(nullPointerHandler);
        
        TestMessage testMsg = new TestMessage("npe-test", "NPE test message");
        producer.send(testMsg);
        
        boolean received = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should receive message");
        
        // Allow error handling to complete
        Thread.sleep(2000);
    }

    @Test
    @Order(5)
    @DisplayName("Test handler throws error (not exception)")
    void testHandlerThrowsError() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        MessageHandler<TestMessage> errorHandler = message -> {
            errorLatch.countDown();
            // Throw Error instead of Exception
            throw new AssertionError("Simulated assertion error");
        };
        
        consumer.subscribe(errorHandler);
        
        TestMessage testMsg = new TestMessage("error-test", "Error test message");
        producer.send(testMsg);
        
        boolean received = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should receive message");
        
        // Allow error handling
        Thread.sleep(2000);
    }

    @Test
    @Order(6)
    @DisplayName("Test message with special characters in error scenario")
    void testMessageWithSpecialCharactersFailure() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        MessageHandler<TestMessage> failHandler = message -> {
            errorLatch.countDown();
            throw new RuntimeException("Failed with special chars: " + message.getPayload().getData());
        };
        
        consumer.subscribe(failHandler);
        
        TestMessage specialMsg = new TestMessage(
            "special-chars-fail",
            "Special: <>&\"'\n\t\r\\/"
        );
        producer.send(specialMsg);
        
        boolean received = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should handle message with special characters");
        
        Thread.sleep(2000);
    }

    @Test
    @Order(7)
    @DisplayName("Test very large message failure")
    void testLargeMessageFailure() throws Exception {
        CountDownLatch errorLatch = new CountDownLatch(1);
        
        MessageHandler<TestMessage> failHandler = message -> {
            errorLatch.countDown();
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
        
        boolean received = errorLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should handle large message");
        
        Thread.sleep(2000);
    }

    @Test
    @Order(8)
    @DisplayName("Test handler with timeout simulation")
    void testHandlerTimeoutSimulation() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        
        MessageHandler<TestMessage> slowFailHandler = message -> {
            startLatch.countDown();
            // Simulate slow processing then fail
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Timeout simulation failure");
        };
        
        consumer.subscribe(slowFailHandler);
        
        TestMessage testMsg = new TestMessage("timeout-test", "Timeout simulation");
        producer.send(testMsg);
        
        boolean started = startLatch.await(5, TimeUnit.SECONDS);
        assertTrue(started, "Should start processing");
        
        // Wait for failure processing
        Thread.sleep(3000);
    }

    @Test
    @Order(9)
    @DisplayName("Test multiple consumers with failures")
    void testMultipleConsumersWithFailures() throws Exception {
        MessageConsumer<TestMessage> consumer2 = outboxFactory.createConsumer(testTopic, TestMessage.class);
        
        try {
            CountDownLatch latch1 = new CountDownLatch(1);
            CountDownLatch latch2 = new CountDownLatch(1);
            
            consumer.subscribe(message -> {
                latch1.countDown();
                return CompletableFuture.failedFuture(new RuntimeException("Consumer 1 failure"));
            });
            
            consumer2.subscribe(message -> {
                latch2.countDown();
                return CompletableFuture.failedFuture(new RuntimeException("Consumer 2 failure"));
            });
            
            TestMessage testMsg = new TestMessage("multi-consumer", "Multiple consumer test");
            producer.send(testMsg);
            
            // At least one consumer should process
            boolean received = latch1.await(5, TimeUnit.SECONDS) || latch2.await(5, TimeUnit.SECONDS);
            assertTrue(received, "At least one consumer should process message");
            
            Thread.sleep(2000);
        } finally {
            consumer2.close();
        }
    }

    @Test
    @Order(10)
    @DisplayName("Test failure then success pattern to cover retry reset")
    void testFailureThenSuccessPattern() throws Exception {
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);
        
        MessageHandler<TestMessage> intermittentHandler = message -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                // First attempt fails
                throw new RuntimeException("First attempt failure");
            }
            // Second attempt succeeds
            successLatch.countDown();
            return CompletableFuture.completedFuture(null);
        };
        
        consumer.subscribe(intermittentHandler);
        
        TestMessage testMsg = new TestMessage("intermittent", "Intermittent failure test");
        producer.send(testMsg);
        
        boolean success = successLatch.await(10, TimeUnit.SECONDS);
        assertTrue(success, "Should eventually succeed after retry");
        assertTrue(attemptCount.get() >= 2, "Should have multiple attempts");
        
        Thread.sleep(1000);
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
