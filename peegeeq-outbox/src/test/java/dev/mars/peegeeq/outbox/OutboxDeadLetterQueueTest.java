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

import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.deadletter.DeadLetterMessage;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for verifying dead letter queue integration with direct exception handling.
 */
@Testcontainers
public class OutboxDeadLetterQueueTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDeadLetterQueueTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

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
        if (manager != null) manager.close();
    }

    @Test
    void testDirectExceptionMovesToDeadLetterQueue() throws Exception {
        logger.info("=== Testing Direct Exception Moves to Dead Letter Queue ===");
        
        String testMessage = "Message that should go to DLQ";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Initial + 3 retries (max-retries=2 means retry up to 2 times, then one more attempt)

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that always fails with direct exception
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: DLQ test attempt {} for message: {}", 
                attempt, message.getPayload());
            retryLatch.countDown();
            
            throw new RuntimeException("INTENTIONAL FAILURE: Should go to DLQ, attempt " + attempt);
        });

        // Wait for all retry attempts
        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times before DLQ");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts (1 initial + 3 retries)");
        
        // Wait for DLQ processing
        Thread.sleep(2000);
        
        // Verify message is in dead letter queue
        List<DeadLetterMessage> dlqMessages = manager.getDeadLetterQueueManager()
            .getDeadLetterMessages("test-dlq-integration", 10, 0);
        
        assertFalse(dlqMessages.isEmpty(), "Should have at least one message in dead letter queue");
        
        DeadLetterMessage dlqMessage = dlqMessages.get(0);
        assertEquals("test-dlq-integration", dlqMessage.getTopic(), "DLQ message should have correct topic");
        assertTrue(dlqMessage.getFailureReason().contains("Should go to DLQ"), 
            "DLQ message should contain failure reason");
        assertEquals(3, dlqMessage.getRetryCount(), "DLQ message should show 3 retries (max-retries=2 means it retries until retry_count=3)");
        
        logger.info("✅ Direct exception DLQ integration test completed successfully");
    }

    @Test
    void testDLQErrorInformationPreservation() throws Exception {
        logger.info("=== Testing DLQ Error Information Preservation ===");
        
        String testMessage = "Message with detailed error info";
        String customErrorMessage = "Custom business validation failed: Invalid order amount";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Error info test attempt {} for message: {}", 
                attempt, message.getPayload());
            retryLatch.countDown();
            
            throw new IllegalArgumentException(customErrorMessage + " (attempt " + attempt + ")");
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should complete all retry attempts");
        
        // Wait for DLQ processing
        Thread.sleep(2000);
        
        // Verify error information in DLQ
        List<DeadLetterMessage> dlqMessages = manager.getDeadLetterQueueManager()
            .getDeadLetterMessages("test-dlq-integration", 10, 0);
        
        assertFalse(dlqMessages.isEmpty(), "Should have message in DLQ");
        
        DeadLetterMessage dlqMessage = dlqMessages.get(0);
        assertTrue(dlqMessage.getFailureReason().contains(customErrorMessage), 
            "DLQ should preserve custom error message");
        assertTrue(dlqMessage.getFailureReason().contains("IllegalArgumentException"), 
            "DLQ should include exception type information");
        
        logger.info("✅ DLQ error information preservation test completed successfully");
    }
}
