package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;

/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for error handling, retry mechanisms, and failure scenarios in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxErrorHandlingTest {

    @Container
    private static final PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("testdb");
        container.withUsername("testuser");
        container.withPassword("testpass");
        return container;
    }

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic for each test to avoid interference
        testTopic = "error-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("error-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        if (manager != null) {
            manager.closeReactive().onComplete(ar -> testContext.completeNow());
        } else {
            testContext.completeNow();
        }
        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testMessageProcessingFailureAndRetry(VertxTestContext testContext) throws Exception {
        String testMessage = "Message that will fail initially";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint successCheckpoint = testContext.checkpoint();

        // Set up consumer that fails first few times
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            System.out.println("Processing attempt " + attempt + " for message: " + message.getPayload());
            
            if (attempt < 3) {
                // Fail the first 2 attempts
                System.out.println("INTENTIONAL FAILURE: Simulating processing failure on attempt " + attempt);
                return Future.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                System.out.println("SUCCESS: Processing succeeded on attempt " + attempt);
                successCheckpoint.flag();
                return Future.succeededFuture();
            }
        });

        // Send the message
        producer.send(testMessage).onFailure(testContext::failNow);

        // Wait for eventual success (should retry and eventually succeed)
        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS), 
            "Message should eventually be processed successfully after retries");
        assertTrue(attemptCount.get() >= 3, 
            "Should have made at least 3 attempts (2 failures + 1 success)");
    }

    @Test
    void testConsumerExceptionHandling(VertxTestContext testContext) throws Exception {
        String testMessage = "Message that causes exception";
        AtomicInteger exceptionCount = new AtomicInteger(0);
        Checkpoint exceptionCheckpoint = testContext.checkpoint();

        // Set up consumer that always throws exception
        consumer.subscribe(message -> {
            int count = exceptionCount.incrementAndGet();
            System.out.println("INTENTIONAL FAILURE: Processing attempt " + count + ", throwing exception");
            exceptionCheckpoint.flag();
            throw new RuntimeException("Intentional exception for testing");
        });

        // Send the message
        producer.send(testMessage).onFailure(testContext::failNow);

        // Wait for at least one exception to be thrown
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), 
            "Consumer should throw exception when processing message");
        assertTrue(exceptionCount.get() >= 1, 
            "Should have thrown at least one exception");
    }

    @Test
    void testProducerWithClosedConnection(VertxTestContext testContext) throws Exception {
        System.out.println("🔌 ===== RUNNING INTENTIONAL CLOSED CONNECTION TEST =====");
        System.out.println("🔌 **INTENTIONAL TEST** - This test deliberately closes the producer and attempts to send a message");
        System.out.println("🔌 **INTENTIONAL TEST FAILURE** - Expected exception when sending with closed producer");

        String testMessage = "Message after close";

        // Close the producer
        producer.close();

        // Try to send message with closed producer — should fail
        producer.send(testMessage).onComplete(ar -> testContext.verify(() -> {
            assertTrue(ar.failed(), "Sending with closed producer should fail");
            System.out.println("🔌 **SUCCESS** - Closed producer properly threw exception");
            System.out.println("🔌 ===== INTENTIONAL TEST COMPLETED =====");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testConsumerUnsubscribe(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger receivedCount = new AtomicInteger(0);
        Promise<Void> firstMessageReceived = Promise.promise();

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = receivedCount.incrementAndGet();
            System.out.println("Received message " + count + ": " + message.getPayload());
            firstMessageReceived.tryComplete();
            return Future.succeededFuture();
        });

        // Send first message, wait for delivery, then unsubscribe and verify isolation
        producer.send("First message")
            .compose(v -> firstMessageReceived.future())
            .compose(v -> {
                testContext.verify(() -> assertEquals(1, receivedCount.get(), "Should have received exactly one message"));
                consumer.unsubscribe();
                return producer.send("Second message after unsubscribe");
            })
            .compose(v -> vertx.timer(3000))
            .onSuccess(timerId -> {
                testContext.verify(() ->
                    assertEquals(1, receivedCount.get(), "Should not receive messages after unsubscribe")
                );
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testConsumerClose(Vertx vertx, VertxTestContext testContext) throws Exception {
        AtomicInteger receivedCount = new AtomicInteger(0);
        Promise<Void> firstMessageReceived = Promise.promise();

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = receivedCount.incrementAndGet();
            System.out.println("Received message " + count + ": " + message.getPayload());
            firstMessageReceived.tryComplete();
            return Future.succeededFuture();
        });

        // Send first message, wait for delivery, then close consumer and verify isolation
        producer.send("Message before close")
            .compose(v -> firstMessageReceived.future())
            .compose(v -> {
                testContext.verify(() -> assertEquals(1, receivedCount.get(), "Should have received exactly one message"));
                consumer.close();
                return producer.send("Message after close");
            })
            .compose(v -> vertx.timer(3000))
            .onSuccess(timerId -> {
                testContext.verify(() ->
                    assertEquals(1, receivedCount.get(), "Should not receive messages after consumer is closed")
                );
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);

        assertTrue(testContext.awaitCompletion(30, TimeUnit.SECONDS));
    }

    @Test
    void testNullMessageHandling() throws Exception {
        System.out.println("❌ ===== RUNNING INTENTIONAL NULL MESSAGE TEST =====");
        System.out.println("❌ **INTENTIONAL TEST** - This test deliberately sends a null payload");
        System.out.println("❌ **INTENTIONAL TEST FAILURE** - Expected exception when sending null payload");

        // Test sending null payload
        assertThrows(Exception.class, () -> {
            producer.send(null);
        }, "Sending null payload should throw exception");

        System.out.println("❌ **SUCCESS** - Null payload properly threw exception");
        System.out.println("❌ ===== INTENTIONAL TEST COMPLETED =====");
    }

    @Test
    void testLargeMessageHandling(VertxTestContext testContext) throws Exception {
        // Create a large message (1MB)
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeMessage.append("This is a large message for testing purposes. ");
        }
        
        String testMessage = largeMessage.toString();
        Checkpoint checkpoint = testContext.checkpoint();
        AtomicInteger receivedCount = new AtomicInteger(0);

        // Set up consumer
        consumer.subscribe(message -> {
            receivedCount.incrementAndGet();
            checkpoint.flag();
            return Future.succeededFuture();
        });

        // Send large message
        producer.send(testMessage).onFailure(testContext::failNow);

        // Wait for message to be received
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), 
            "Large message should be received within timeout");
        assertEquals(1, receivedCount.get(), 
            "Should receive exactly one large message");
    }
}


