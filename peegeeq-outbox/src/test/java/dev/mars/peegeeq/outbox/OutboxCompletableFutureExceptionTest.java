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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for CompletableFuture-based exception handling in outbox consumers.
 * 
 * Tests verify that exceptions returned in failed CompletableFutures are properly
 * processed through retry and dead letter queue mechanisms.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxCompletableFutureExceptionTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxCompletableFutureExceptionTest.class);

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
        producer = factory.createProducer("test-future-exceptions", String.class);
        consumer = factory.createConsumer("test-future-exceptions", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (manager != null) manager.close();
    }

    @Test
    void testFailedCompletableFutureHandling() throws Exception {
        logger.info("=== Testing Failed CompletableFuture Handling ===");
        
        String testMessage = "Message that returns failed future";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that returns failed CompletableFuture
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for failed future", attempt);
            retryLatch.countDown();
            
            // Return failed CompletableFuture - should be handled correctly
            return CompletableFuture.failedFuture(
                new RuntimeException("INTENTIONAL FAILURE: Failed future from handler, attempt " + attempt)
            );
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times for failed future");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Failed CompletableFuture handling test completed successfully");
    }

    @Test
    void testAsyncFailureHandling() throws Exception {
        logger.info("=== Testing Async Failure Handling ===");
        
        String testMessage = "Message that fails asynchronously";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that fails asynchronously
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for async failure", attempt);
            retryLatch.countDown();
            
            // Return future that fails asynchronously
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(50); // Simulate async work
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("INTENTIONAL FAILURE: Async failure, attempt " + attempt);
            }).thenRun(() -> {
                // This won't be reached due to exception above
            });
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times for async failure");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Async failure handling test completed successfully");
    }

    @Test
    void testTimeoutExceptionHandling() throws Exception {
        logger.info("=== Testing Timeout Exception Handling ===");
        
        String testMessage = "Message that times out";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch retryLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that times out
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for timeout", attempt);
            retryLatch.countDown();
            
            // Return future that times out
            CompletableFuture<Void> future = new CompletableFuture<>();
            
            // Schedule timeout failure
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
                future.completeExceptionally(
                    new RuntimeException("INTENTIONAL FAILURE: Timeout exception, attempt " + attempt)
                );
            });
            
            return future;
        });

        boolean completed = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times for timeout");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Timeout exception handling test completed successfully");
    }

    @Test
    void testNullCompletableFutureHandling() throws Exception {
        logger.info("=== Testing Null CompletableFuture Handling ===");
        
        String testMessage = "Message that returns null future";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch errorLatch = new CountDownLatch(1);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that returns null (should cause NPE)
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} returning null", attempt);
            errorLatch.countDown();
            
            // Return null - should cause NPE and be handled
            return null;
        });

        boolean completed = errorLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing and failed with null return");
        assertTrue(attemptCount.get() >= 1, "Should have made at least 1 processing attempt");
        
        logger.info("✅ Null CompletableFuture handling test completed successfully");
    }
}
