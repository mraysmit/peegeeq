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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.api.deadletter.DeadLetterMessageInfo;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test suite for verifying dead letter queue integration with direct exception handling.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class OutboxDeadLetterQueueTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDeadLetterQueueTest.class);

    @Container
    static PostgreSQLContainer postgres = createPostgresContainer();

    private static PostgreSQLContainer createPostgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:15.13-alpine3.20");
        container.withDatabaseName("peegeeq_test");
        container.withUsername("test");
        container.withPassword("test");
        return container;
    }

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.max-retries", "2");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();

        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        producer = factory.createProducer("test-dlq-integration", String.class);
        consumer = factory.createConsumer("test-dlq-integration", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (manager != null) {
            CountDownLatch closeLatch = new CountDownLatch(1);
            manager.closeReactive().onComplete(ar -> closeLatch.countDown());
            closeLatch.await(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void testDirectExceptionMovesToDeadLetterQueue(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Direct Exception Moves to Dead Letter Queue ===");
        
        String testMessage = "Message that should go to DLQ";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(4); // Initial + 3 retries

        CountDownLatch sendLatch1 = new CountDownLatch(1);
        producer.send(testMessage).onComplete(ar -> sendLatch1.countDown());
        assertTrue(sendLatch1.await(5, TimeUnit.SECONDS), "Send should complete");

        // Set up consumer that always fails with direct exception
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: DLQ test attempt {} for message: {}", 
                attempt, message.getPayload());
            retryCheckpoint.flag();
            
            throw new RuntimeException("INTENTIONAL FAILURE: Should go to DLQ, attempt " + attempt);
        });

        // Wait for all retry attempts
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Should have attempted processing 3 times before DLQ");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts (1 initial + 3 retries)");
        
        // Wait for DLQ processing
        CountDownLatch timerLatch1 = new CountDownLatch(1);
        vertx.timer(2000).onComplete(ar -> timerLatch1.countDown());
        assertTrue(timerLatch1.await(5, TimeUnit.SECONDS), "Timer should complete");
        
        // Verify message is in dead letter queue
        CountDownLatch dlqLatch1 = new CountDownLatch(1);
        AtomicReference<List<DeadLetterMessageInfo>> dlqRef1 = new AtomicReference<>();
        manager.getDeadLetterQueueManager()
            .getDeadLetterMessages("test-dlq-integration", 10, 0)
            .onSuccess(msgs -> {
                dlqRef1.set(msgs);
                dlqLatch1.countDown();
            })
            .onFailure(t -> {
                testContext.failNow(t);
                dlqLatch1.countDown();
            });
        assertTrue(dlqLatch1.await(5, TimeUnit.SECONDS), "Should retrieve DLQ messages");
        List<DeadLetterMessageInfo> dlqMessages = dlqRef1.get();
        
        assertFalse(dlqMessages.isEmpty(), "Should have at least one message in dead letter queue");
        
        DeadLetterMessageInfo dlqMessage = dlqMessages.get(0);
        assertEquals("test-dlq-integration", dlqMessage.topic(), "DLQ message should have correct topic");
        assertTrue(dlqMessage.failureReason().contains("Should go to DLQ"),
            "DLQ message should contain failure reason");
        assertEquals(3, dlqMessage.retryCount(), "DLQ message should show 3 retries (max-retries=2 means it retries until retry_count=3)");
        
        logger.info("Direct exception DLQ integration test completed successfully");
    }

    @Test
    void testDLQErrorInformationPreservation(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=== Testing DLQ Error Information Preservation ===");
        
        String testMessage = "Message with detailed error info";
        String customErrorMessage = "Custom business validation failed: Invalid order amount";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        CountDownLatch sendLatch2 = new CountDownLatch(1);
        producer.send(testMessage).onComplete(ar -> sendLatch2.countDown());
        assertTrue(sendLatch2.await(5, TimeUnit.SECONDS), "Send should complete");

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Error info test attempt {} for message: {}", 
                attempt, message.getPayload());
            retryCheckpoint.flag();
            
            throw new IllegalArgumentException(customErrorMessage + " (attempt " + attempt + ")");
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Should complete all retry attempts");
        
        // Wait for DLQ processing
        CountDownLatch timerLatch2 = new CountDownLatch(1);
        vertx.timer(2000).onComplete(ar -> timerLatch2.countDown());
        assertTrue(timerLatch2.await(5, TimeUnit.SECONDS), "Timer should complete");
        
        // Verify error information in DLQ
        CountDownLatch dlqLatch2 = new CountDownLatch(1);
        AtomicReference<List<DeadLetterMessageInfo>> dlqRef2 = new AtomicReference<>();
        manager.getDeadLetterQueueManager()
            .getDeadLetterMessages("test-dlq-integration", 10, 0)
            .onSuccess(msgs -> {
                dlqRef2.set(msgs);
                dlqLatch2.countDown();
            })
            .onFailure(t -> {
                testContext.failNow(t);
                dlqLatch2.countDown();
            });
        assertTrue(dlqLatch2.await(5, TimeUnit.SECONDS), "Should retrieve DLQ messages");
        List<DeadLetterMessageInfo> dlqMessages = dlqRef2.get();
        
        assertFalse(dlqMessages.isEmpty(), "Should have message in DLQ");
        
        DeadLetterMessageInfo dlqMessage = dlqMessages.get(0);
        assertTrue(dlqMessage.failureReason().contains(customErrorMessage),
            "DLQ should preserve custom error message");
        assertTrue(dlqMessage.failureReason().contains("IllegalArgumentException"),
            "DLQ should include exception type information");
        
        logger.info("DLQ error information preservation test completed successfully");
    }
}


