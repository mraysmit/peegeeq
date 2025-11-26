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
 * Test suite for edge cases and error conditions in outbox exception handling.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxEdgeCasesTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxEdgeCasesTest.class);

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
        producer = factory.createProducer("test-edge-cases", String.class);
        consumer = factory.createConsumer("test-edge-cases", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (manager != null) manager.close();
    }

    @Test
    void testNullCompletableFutureReturn() throws Exception {
        logger.info("=== Testing Null CompletableFuture Return ===");
        
        String testMessage = "Message that returns null future";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch errorLatch = new CountDownLatch(1);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that returns null CompletableFuture
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} returning null CompletableFuture", attempt);
            errorLatch.countDown();
            
            // Return null - should cause NPE and be handled as direct exception
            return null;
        });

        boolean completed = errorLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing and failed with null return");
        assertTrue(attemptCount.get() >= 1, "Should have made at least 1 processing attempt");
        
        logger.info("✅ Null CompletableFuture return test completed successfully");
    }

    @Test
    void testExceptionDuringMessageAccess() throws Exception {
        logger.info("=== Testing Exception During Message Access ===");
        
        String testMessage = "Message for access exception test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch errorLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that throws exception when accessing message properties
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} with message access exception", attempt);
            errorLatch.countDown();
            
            // Try to access message properties in a way that might cause exception
            String payload = message.getPayload();
            if (payload != null) {
                // Simulate exception during message processing
                throw new IllegalStateException("INTENTIONAL FAILURE: Exception during message access, attempt " + attempt);
            }
            
            return CompletableFuture.completedFuture(null);
        });

        boolean completed = errorLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ Exception during message access test completed successfully");
    }

    @Test
    void testInterruptedExceptionHandling() throws Exception {
        logger.info("=== Testing InterruptedException Handling ===");
        
        String testMessage = "Message that gets interrupted";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch errorLatch = new CountDownLatch(3);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} with interruption", attempt);
            errorLatch.countDown();
            
            // Simulate interrupted exception
            Thread.currentThread().interrupt();
            throw new RuntimeException("INTENTIONAL FAILURE: Thread interrupted, attempt " + attempt, 
                new InterruptedException("Simulated interruption"));
        });

        boolean completed = errorLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing 3 times");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("✅ InterruptedException handling test completed successfully");
    }

    @Test
    void testOutOfMemoryErrorHandling() throws Exception {
        logger.info("=== Testing OutOfMemoryError Simulation ===");
        
        String testMessage = "Message that simulates OOM";
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch errorLatch = new CountDownLatch(1);

        producer.send(testMessage).get(5, TimeUnit.SECONDS);

        // Set up consumer that simulates OOM
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} simulating OOM", attempt);
            errorLatch.countDown();
            
            // Simulate OOM by throwing it directly (safer than actually causing OOM)
            throw new OutOfMemoryError("INTENTIONAL FAILURE: Simulated OOM, attempt " + attempt);
        });

        boolean completed = errorLatch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "Should have attempted processing and handled OOM simulation");
        assertTrue(attemptCount.get() >= 1, "Should have made at least 1 processing attempt");
        
        logger.info("✅ OutOfMemoryError simulation test completed successfully");
    }
}
