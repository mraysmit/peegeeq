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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for verifying retry logic behavior with direct exceptions.
 */
@Testcontainers
public class OutboxRetryLogicTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRetryLogicTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("peegeeq_test")
            .withUsername("test")
            .withPassword("test");

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S");

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("test"), new SimpleMeterRegistry());
        manager.start();

        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
        OutboxFactoryRegistrar.registerWith(provider);
        
        QueueFactory factory = provider.createFactory("outbox", databaseService);
        producer = factory.createProducer("test-retry-logic", String.class);
        consumer = factory.createConsumer("test-retry-logic", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (manager != null) manager.close();
    }

    @Test
    void testRetryCountIncrementsCorrectly() throws Exception {
        logger.info("=== Testing Retry Count Increments ===");
        
        String testMessage = "Message for retry count test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Initial + 3 retries

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that always fails with direct exception
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Retry attempt {} for message: {}", 
                attempt, message.getPayload());
            retryLatch.countDown();
            
            throw new RuntimeException("INTENTIONAL FAILURE: Always fail for retry test, attempt " + attempt);
        });

        // Wait for all retry attempts
        boolean completed = retryLatch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times (initial + 3 retries)");
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 processing attempts");
        
        logger.info("✅ Retry count increment test completed successfully");
    }

    @Test
    void testMaxRetriesThresholdRespected() throws Exception {
        logger.info("=== Testing Max Retries Threshold ===");
        
        String testMessage = "Message for max retries test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4); // Should stop at 4 (initial + 3 retries)

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Max retries attempt {} for message: {}", 
                attempt, message.getPayload());
            retryLatch.countDown();
            
            throw new RuntimeException("INTENTIONAL FAILURE: Testing max retries, attempt " + attempt);
        });

        // Wait for all attempts
        boolean completed = retryLatch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing exactly 4 times");
        assertEquals(4, attemptCount.get(), "Should respect max retries limit");
        
        // Wait a bit more to ensure no additional attempts
        Thread.sleep(2000);
        assertEquals(4, attemptCount.get(), "Should not exceed max retries");
        
        logger.info("✅ Max retries threshold test completed successfully");
    }

    @Test
    void testEventualSuccessAfterRetries() throws Exception {
        logger.info("=== Testing Eventual Success After Retries ===");
        
        String testMessage = "Message that eventually succeeds";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch successLatch = new CountDownLatch(1);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            
            if (attempt < 3) {
                logger.info("INTENTIONAL FAILURE: Failing attempt {} for eventual success test", attempt);
                throw new RuntimeException("INTENTIONAL FAILURE: Failing on purpose, attempt " + attempt);
            } else {
                logger.info("SUCCESS: Succeeding on attempt {} for eventual success test", attempt);
                successLatch.countDown();
                return CompletableFuture.completedFuture(null);
            }
        });

        boolean completed = successLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should eventually succeed after retries");
        assertEquals(3, attemptCount.get(), "Should succeed on 3rd attempt");
        
        logger.info("✅ Eventual success after retries test completed successfully");
    }

    @Test
    void testDifferentExceptionTypesRetryBehavior() throws Exception {
        logger.info("=== Testing Different Exception Types Retry Behavior ===");
        
        // Test that different exception types all trigger retry logic
        testExceptionTypeRetry("IllegalArgumentException", 
            () -> new IllegalArgumentException("INTENTIONAL FAILURE: Invalid argument"));
        
        testExceptionTypeRetry("IllegalStateException", 
            () -> new IllegalStateException("INTENTIONAL FAILURE: Invalid state"));
        
        testExceptionTypeRetry("NullPointerException", 
            () -> new NullPointerException("INTENTIONAL FAILURE: Null pointer"));
        
        logger.info("✅ Different exception types retry test completed successfully");
    }

    private void testExceptionTypeRetry(String exceptionType, java.util.function.Supplier<RuntimeException> exceptionSupplier) throws Exception {
        logger.info("Testing retry behavior for: {}", exceptionType);
        
        String testMessage = "Message for " + exceptionType + " test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(4);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: {} attempt {} for message: {}", 
                exceptionType, attempt, message.getPayload());
            retryLatch.countDown();
            
            throw exceptionSupplier.get();
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 4 times for " + exceptionType);
        assertEquals(4, attemptCount.get(), "Should have made exactly 4 attempts for " + exceptionType);
        
        // Reset for next test
        consumer.unsubscribe();
        Thread.sleep(500);
    }
}
