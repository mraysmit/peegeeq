package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
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

    private static final Logger logger = LoggerFactory.getLogger(OutboxErrorHandlingTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

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
        manager.start().await();

        // Create factory and components
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
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
            manager.closeReactive()
                    .onSuccess(v -> testContext.completeNow())
                    .onFailure(testContext::failNow);
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
        logger.info("Test: message processing failure and retry");
            int attempt = attemptCount.incrementAndGet();
            logger.info("Processing attempt {} for message: {}", attempt, message.getPayload());
            
            if (attempt < 3) {
                // Fail the first 2 attempts
                logger.info("INTENTIONAL FAILURE: Simulating processing failure on attempt {}", attempt);
                return Future.failedFuture(
                    new RuntimeException("Simulated processing failure, attempt " + attempt));
            } else {
                // Succeed on the 3rd attempt
                logger.info("SUCCESS: Processing succeeded on attempt {}", attempt);
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
        logger.info("Test: consumer exception handling");
            int count = exceptionCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {}, throwing exception", count);
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
        logger.info("===== RUNNING INTENTIONAL CLOSED CONNECTION TEST =====");
        logger.info("**INTENTIONAL TEST** - This test deliberately closes the producer and attempts to send a message");
        logger.info("**INTENTIONAL TEST FAILURE** - Expected exception when sending with closed producer");

        String testMessage = "Message after close";

        // Close the producer
        producer.close();

        // Try to send message with closed producer should fail
        producer.send(testMessage).onComplete(ar -> testContext.verify(() -> {
        logger.info("Test: producer with closed connection");
            assertTrue(ar.failed(), "Sending with closed producer should fail");
            logger.info("**SUCCESS** - Closed producer properly threw exception");
            logger.info("===== INTENTIONAL TEST COMPLETED =====");
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
        logger.info("Test: consumer unsubscribe");
            int count = receivedCount.incrementAndGet();
            logger.info("Received message {}: {}", count, message.getPayload());
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
        logger.info("Test: consumer close");
            int count = receivedCount.incrementAndGet();
            logger.info("Received message {}: {}", count, message.getPayload());
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
    void testNullMessageHandling(VertxTestContext testContext) throws Exception {
        logger.info("===== RUNNING INTENTIONAL NULL MESSAGE TEST =====");
        logger.info("**INTENTIONAL TEST** - This test deliberately sends a null payload");
        logger.info("**INTENTIONAL TEST FAILURE** - Expected exception when sending null payload");

        // producer.send(null) returns a failed Future (does not throw synchronously)
        producer.send(null).onComplete(ar -> testContext.verify(() -> {
        logger.info("Test: null message handling");
            assertTrue(ar.failed(), "Sending null payload should return a failed Future");
            assertTrue(ar.cause() instanceof IllegalArgumentException,
                "Cause should be IllegalArgumentException, got: " + ar.cause().getClass().getSimpleName());
            logger.info("**SUCCESS** - Null payload properly returned failed Future");
            logger.info("===== INTENTIONAL TEST COMPLETED =====");
            testContext.completeNow();
        }));

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS));
    }

    @Test
    void testLargeMessageHandling(VertxTestContext testContext) throws Exception {
        // Create a large message (1MB)
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
        logger.info("Test: large message handling");
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


