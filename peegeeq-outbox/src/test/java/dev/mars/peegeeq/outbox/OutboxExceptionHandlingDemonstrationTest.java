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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * DEMONSTRATION TEST: Shows the fix for direct exception handling in outbox consumers.
 * 
 * This test clearly demonstrates that the bug has been fixed:
 * - Before fix: Direct exceptions from MessageHandler.handle() were NOT caught
 * - After fix: Direct exceptions are caught and processed through retry/DLQ logic
 * 
 * The test output will show "Message processing failed" log entries, proving that
 * direct exceptions are now being caught by the .exceptionally() handler.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxExceptionHandlingDemonstrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxExceptionHandlingDemonstrationTest.class);

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
        producer = factory.createProducer("exception-fix-demo", String.class);
        consumer = factory.createConsumer("exception-fix-demo", String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
        if (manager != null) {
            manager.closeReactive().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test
    void demonstrateDirectExceptionHandlingFix(VertxTestContext testContext) throws Exception {
        logger.info("=================================================================");
        logger.info("DEMONSTRATION: Direct Exception Handling Fix");
        logger.info("=================================================================");
        logger.info("This test demonstrates that direct exceptions thrown from");
        logger.info("MessageHandler.handle() are now properly caught and processed");
        logger.info("through the retry and dead letter queue mechanisms.");
        logger.info("=================================================================");
        
        String testMessage = "DEMO: Message that throws direct exception";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3); // Initial + 2 retries

        // Send the test message
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        logger.info("Sent test message: {}", testMessage);

        // Set up consumer that throws exception DIRECTLY from the handler method
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} for message: {}", 
                attempt, message.getPayload());
            retryCheckpoint.flag();
            
            // CRITICAL: This throws directly from the handler method
            // Before fix: This would NOT be caught by .exceptionally() handler
            // After fix: This IS caught and converted to failed CompletableFuture
            throw new RuntimeException("INTENTIONAL FAILURE: Direct exception from handler, attempt " + attempt);
        });

        logger.info("⏳ Waiting for retry attempts to complete...");
        
        // Wait for all retry attempts
        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should have attempted processing 3 times (initial + 2 retries)");
        
        logger.info("=================================================================");
        logger.info("RESULTS:");
        logger.info("  Total processing attempts: {}", attemptCount.get());
        logger.info("  Expected attempts: 3 (initial + 2 retries)");
        logger.info("=================================================================");
        
        // Assertions
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("DEMONSTRATION TEST COMPLETED SUCCESSFULLY");
        logger.info("The outbox consumer exception handling bug has been FIXED!");
    }

    @Test
    void demonstrateBeforeAndAfterBehavior(Vertx vertx, VertxTestContext testContext) throws Exception {
        logger.info("=================================================================");
        logger.info("BEFORE vs AFTER COMPARISON");
        logger.info("=================================================================");
        logger.info("This test shows the difference between the two exception patterns:");
        logger.info("1. Direct exceptions (FIXED by this change)");
        logger.info("2. CompletableFuture exceptions (always worked)");
        logger.info("=================================================================");
        
        // Test 1: Direct Exception (now fixed)
        testDirectExceptionPattern(testContext);
        
        // Reset consumer for next test
        consumer.unsubscribe();
        // GC-settle: allow unsubscribe to complete
        vertx.timer(500).toCompletionStage().toCompletableFuture().join();
        
        // Test 2: CompletableFuture Exception (always worked)
        testCompletableFuturePattern(testContext);
        
        logger.info("=================================================================");
        logger.info("CONCLUSION: Both patterns now work identically!");
        logger.info("Direct exceptions and CompletableFuture exceptions are");
        logger.info("handled consistently through the same retry/DLQ logic!");
        logger.info("=================================================================");
    }

    private void testDirectExceptionPattern(VertxTestContext testContext) throws Exception {
        logger.info("--- Testing Pattern 1: Direct Exception (FIXED) ---");
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        producer.send("Direct exception test").get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Direct exception attempt {} for: {}", 
                attempt, message.getPayload());
            retryCheckpoint.flag();
            
            // Pattern 1: Throw exception directly (NOW WORKS)
            throw new RuntimeException("INTENTIONAL FAILURE: Direct exception, attempt " + attempt);
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Direct exceptions should trigger retry logic");
        assertEquals(3, attemptCount.get(), "Should have 3 attempts for direct exception");
        
        logger.info("Pattern 1 (Direct Exception): {} attempts - WORKING", attemptCount.get());
    }

    private void testCompletableFuturePattern(VertxTestContext testContext) throws Exception {
        logger.info("--- Testing Pattern 2: CompletableFuture Exception (Always worked) ---");
        
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        producer.send("CompletableFuture exception test").get(5, TimeUnit.SECONDS);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: CompletableFuture exception attempt {} for: {}", 
                attempt, message.getPayload());
            retryCheckpoint.flag();
            
            // Pattern 2: Return failed CompletableFuture (ALWAYS WORKED)
            return CompletableFuture.failedFuture(
                new RuntimeException("INTENTIONAL FAILURE: Failed future, attempt " + attempt)
            );
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "CompletableFuture exceptions should trigger retry logic");
        assertEquals(3, attemptCount.get(), "Should have 3 attempts for CompletableFuture exception");
        
        logger.info("Pattern 2 (CompletableFuture Exception): {} attempts - WORKING", attemptCount.get());
    }
}


