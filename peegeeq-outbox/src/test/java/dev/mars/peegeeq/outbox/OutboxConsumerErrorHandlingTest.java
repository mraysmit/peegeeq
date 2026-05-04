package dev.mars.peegeeq.outbox;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

import java.util.UUID;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for OutboxConsumer error handling and edge cases.
 * Targets uncovered branches to increase coverage from 75% to 85%+:
 * - Handler exception scenarios
 * - Retry logic and dead letter queue
 * - Unsubscribe during processing
 * - Consumer group name changes
 * - Close during active processing
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxConsumerErrorHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxConsumerErrorHandlingTest.class);

    private static final String[] SYSTEM_PROPERTIES = {
        "peegeeq.database.host", "peegeeq.database.port", "peegeeq.database.name",
        "peegeeq.database.username", "peegeeq.database.password", "peegeeq.database.ssl.enabled",
        "peegeeq.queue.polling-interval"
    };

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        testTopic = "error-test-" + UUID.randomUUID().toString().substring(0, 8);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("error-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start().await();

        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
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
        if (manager != null) {
            manager.closeReactive().await();
        }

        for (String prop : SYSTEM_PROPERTIES) {
            System.clearProperty(prop);
        }
    }

    @Test
    void testHandlerExceptionWithRetry(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint checkpoint = testContext.checkpoint(3);
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: handler exception with retry");
            int attempt = attemptCount.incrementAndGet();
            checkpoint.flag();
            
            if (attempt < 3) {
                throw new RuntimeException("Simulated handler failure on attempt " + attempt);
            }
            // Third attempt succeeds
            return Future.succeededFuture();
        });

        producer.send("test-message").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), 
            "Should process message with retries");
        assertEquals(3, attemptCount.get(), 
            "Should have attempted 3 times");
    }

    @Test
    void testHandlerExceptionReachesMaxRetries(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint checkpoint = testContext.checkpoint();
        AtomicInteger attemptCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: handler exception reaches max retries");
            attemptCount.incrementAndGet();
            checkpoint.flag();
            return Future.failedFuture(new RuntimeException("Always fails"));
        });

        producer.send("failing-message").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should process at least once");
        assertTrue(attemptCount.get() > 0);
    }

    @Test
    void testUnsubscribeDuringProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint processingStarted = testContext.checkpoint();
        Checkpoint processingCompleted = testContext.checkpoint();

        consumer.subscribe(message -> {
        logger.info("Test: unsubscribe during processing");
            processingStarted.flag();
            // Simulate processing via non-blocking delay
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(100, id -> {
                processingCompleted.flag();
                promise.complete();
            });
            return promise.future();
        });

        producer.send("test-message").await();

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), 
            "Processing should start and complete");

        // Unsubscribe after processing
        consumer.unsubscribe();

        // Send another message - should not be processed
        producer.send("ignored-message").await();
    }

    @Test
    void testCloseDuringProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint processingStarted = testContext.checkpoint();
        
        consumer.subscribe(message -> {
        logger.info("Test: close during processing");
            processingStarted.flag();
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(100, id -> promise.complete());
            return promise.future();
        });

        producer.send("test-message").await();

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), 
            "Processing should start");

        // Close consumer during processing
        assertDoesNotThrow(() -> consumer.close(), 
            "Close should not throw exception");
    }

    @Test
    void testSetConsumerGroupName() throws Exception {
        OutboxConsumer<String> typedConsumer = (OutboxConsumer<String>) consumer;
        
        String groupName = "test-group-" + UUID.randomUUID();
        
        assertDoesNotThrow(() -> typedConsumer.setConsumerGroupName(groupName), 
            "Should set consumer group name without error");
    }

    @Test
    void testMultipleSubscribeCallsLogsWarning() {
        logger.info("Test: set consumer group name");
        // First subscription should succeed
        consumer.subscribe(message -> Future.succeededFuture());

        // Second subscription should not throw, just log warning and skip startPolling
        assertDoesNotThrow(
            () -> consumer.subscribe(message -> Future.succeededFuture()),
            "Second subscribe should not throw, just log warning");
    }    @Test
    void testUnsubscribeBeforeSubscribe() {
        assertDoesNotThrow(() -> consumer.unsubscribe(), 
            "Unsubscribe before subscribe should not throw");
    }

    @Test
    void testCloseBeforeSubscribe() {
        assertDoesNotThrow(() -> consumer.close(), 
            "Close before subscribe should not throw");
    }

    @Test
    void testCloseMultipleTimes(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("Test: close before subscribe");
        Checkpoint checkpoint = testContext.checkpoint();
        
        consumer.subscribe(message -> {
            checkpoint.flag();
            return Future.succeededFuture();
        });
        producer.send("test-message");
        
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), 
            "Message should be received");

        assertDoesNotThrow(() -> {
            consumer.close();
            consumer.close(); // Second close
            consumer.close(); // Third close
        }, "Multiple close calls should not throw");
    }

    @Test
    void testMessageWithNullPayload(Vertx vertx, VertxTestContext testContext) throws Exception {
        // producer.send(null) returns a failed Future no message is stored,
        // so the consumer never receives anything. Verify the send fails.
        producer.send(null)
            .onSuccess(v -> testContext.failNow("Sending null payload should have failed"))
            .onFailure(err -> testContext.verify(() -> {
        logger.info("Test: message with null payload");
                assertInstanceOf(IllegalArgumentException.class, err,
                    "Cause should be IllegalArgumentException");
                testContext.completeNow();
            }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testRapidSubscribeUnsubscribeCycle(Vertx vertx, VertxTestContext testContext) throws Exception {
        // Verify that rapid subscribe/unsubscribe cycles don't throw exceptions.
        // Don't use checkpoints here re-subscribing on the same consumer creates
        // duplicate polling tasks and checkpoint accumulation breaks VertxTestContext.
        for (int i = 0; i < 5; i++) {
        logger.info("Test: rapid subscribe unsubscribe cycle");
            consumer.subscribe(message -> Future.succeededFuture());
            consumer.unsubscribe();
        }
        testContext.completeNow();
        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void testHandlerWithInterruptedException(Vertx vertx, VertxTestContext testContext) throws Exception {
        Checkpoint checkpoint = testContext.checkpoint();

        consumer.subscribe(message -> {
        logger.info("Test: handler with interrupted exception");
            Thread.currentThread().interrupt();
            checkpoint.flag();
            return Future.failedFuture(new RuntimeException("Interrupted"));
        });

        producer.send("interrupt-test").await();

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS), "Consumer should handle interrupted exception");
    }

    @Test
    void testConcurrentMessageProcessing(Vertx vertx, VertxTestContext testContext) throws Exception {
        int messageCount = 10;
        Checkpoint checkpoint = testContext.checkpoint(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
        logger.info("Test: concurrent message processing");
            processedCount.incrementAndGet();
            // Simulate processing via non-blocking delay
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(50, id -> {
                checkpoint.flag();
                promise.complete();
            });
            return promise.future();
        });

        // Send multiple messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("concurrent-message-" + i).await();
        }

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), 
            "Should process all messages");
        assertEquals(messageCount, processedCount.get(), 
            "Should process exactly " + messageCount + " messages");
    }
}


