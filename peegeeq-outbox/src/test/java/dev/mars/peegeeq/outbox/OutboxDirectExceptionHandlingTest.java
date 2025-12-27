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
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Comprehensive test suite for direct exception handling in outbox consumers.
 * 
 * Tests verify that exceptions thrown directly from MessageHandler.handle() methods
 * are properly caught and processed through the retry and dead letter queue mechanisms.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxDirectExceptionHandlingTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDirectExceptionHandlingTest.class);

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
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Configure system properties for test container
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.max-retries", "2");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");

        // Initialize PeeGeeQ
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();

        // Create outbox factory and producer/consumer
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        producer = factory.createProducer("test-direct-exceptions", String.class);
        consumer = factory.createConsumer("test-direct-exceptions", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (manager != null) manager.close();
    }

    @Test
    void testRuntimeExceptionHandling() throws Exception {
        logger.info("=== Testing Direct RuntimeException Handling ===");
        
        String testMessage = "Message that causes RuntimeException";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3); // Expect 3 attempts (initial + 2 retries)

        // Send the message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that throws RuntimeException directly
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for message: {}", 
                attempt, message.getPayload());
            retryLatch.countDown();
            
            // This throws directly from the handler method - should be caught and handled
            throw new RuntimeException("INTENTIONAL FAILURE: Direct RuntimeException, attempt " + attempt);
        });

        // Wait for all retry attempts
        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times (initial + 2 retries)");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Direct RuntimeException handling test completed successfully");
    }

    @Test
    void testCheckedExceptionHandling() throws Exception {
        logger.info("=== Testing Direct Checked Exception Handling ===");
        
        String testMessage = "Message that causes checked exception";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that throws checked exception (wrapped in RuntimeException)
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for checked exception", attempt);
            retryLatch.countDown();
            
            // Simulate checked exception scenario
            throw new RuntimeException("INTENTIONAL FAILURE: Wrapped IOException", 
                new IOException("Simulated IO failure, attempt " + attempt));
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times for checked exception");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Direct checked exception handling test completed successfully");
    }

    @Test
    void testCustomBusinessExceptionHandling() throws Exception {
        logger.info("=== Testing Custom Business Exception Handling ===");
        
        String testMessage = "Message that causes business exception";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that throws custom business exception
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for business exception", attempt);
            retryLatch.countDown();
            
            // Custom business exception
            throw new BusinessProcessingException("INTENTIONAL FAILURE: Order validation failed", 
                "VALIDATION_ERROR", attempt);
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times for business exception");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Custom business exception handling test completed successfully");
    }

    @Test
    void testNullPointerExceptionHandling() throws Exception {
        logger.info("=== Testing NullPointerException Handling ===");
        
        String testMessage = "Message that causes NPE";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that throws NPE
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for NPE", attempt);
            retryLatch.countDown();

            // Intentionally throw NPE to test exception handling
            throw new NullPointerException("INTENTIONAL TEST FAILURE: Simulated NPE for retry testing");
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times for NPE");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ NullPointerException handling test completed successfully");
    }

    /**
     * Custom business exception for testing
     */
    public static class BusinessProcessingException extends RuntimeException {
        private final String errorCode;
        private final int attemptNumber;
        
        public BusinessProcessingException(String message, String errorCode, int attemptNumber) {
            super(message);
            this.errorCode = errorCode;
            this.attemptNumber = attemptNumber;
        }
        
        public String getErrorCode() { return errorCode; }
        public int getAttemptNumber() { return attemptNumber; }
    }
}
