package dev.mars.peegeeq.outbox;

import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
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
import io.vertx.core.Future;
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

import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Test suite for edge cases and error conditions in outbox exception handling.
 */
@Tag(TestCategories.INTEGRATION)
@ExtendWith(VertxExtension.class)
@Testcontainers
public class OutboxEdgeCasesTest {

    private static final Logger logger = LoggerFactory.getLogger(OutboxEdgeCasesTest.class);

    @Container
    private static final PostgreSQLContainer postgres = PostgreSQLTestConstants.createStandardContainer();

    private PeeGeeQManager manager;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;

    @BeforeEach
    void setUp(VertxTestContext testContext) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, PostgreSQLTestConstants.TEST_SCHEMA, SchemaComponent.QUEUE_ALL);

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres)
                .schema(PostgreSQLTestConstants.TEST_SCHEMA)
                .property("peegeeq.queue.max-retries", "2")
                .property("peegeeq.queue.polling-interval", "PT0.1S")
                .build();

        manager = new PeeGeeQManager(new PeeGeeQConfiguration("default", testProps), new SimpleMeterRegistry());
        manager.start()
            .onSuccess(v -> {
                PgDatabaseService databaseService = new PgDatabaseService(manager);
                PgQueueFactoryProvider provider = new PgQueueFactoryProvider();
                OutboxFactoryRegistrar.registerWith(provider);
                QueueFactory factory = provider.createFactory("outbox", databaseService);
                producer = factory.createProducer("test-edge-cases", String.class);
                consumer = factory.createConsumer("test-edge-cases", String.class);
                testContext.completeNow();
            })
            .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) throws Exception {
        logger.info("Tearing down: closing resources and manager");
        if (consumer != null) consumer.close();
        if (producer != null) producer.close();
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
    void testNullFutureReturn(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Null Future Return ===");
        
        String testMessage = "Message that returns null future";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint errorCheckpoint = testContext.checkpoint();

        producer.send(testMessage).onFailure(testContext::failNow);

        // Set up consumer that returns null Future
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} returning null Future", attempt);
            errorCheckpoint.flag();
            
            // Return null - should cause NPE and be handled as direct exception
            return null;
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should have attempted processing and failed with null return");
        assertTrue(attemptCount.get() >= 1, "Should have made at least 1 processing attempt");
        
        logger.info("Null Future return test completed successfully");
    }

    @Test
    void testExceptionDuringMessageAccess(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Exception During Message Access ===");
        
        String testMessage = "Message for access exception test";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        producer.send(testMessage).onFailure(testContext::failNow);

        // Set up consumer that throws exception when accessing message propertiesfg
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} with message access exception", attempt);
            retryCheckpoint.flag();
            
            // Try to access message properties in a way that might cause exception
            String payload = message.getPayload();
            if (payload != null) {
                // Simulate exception during message processing
                throw new IllegalStateException("INTENTIONAL FAILURE: Exception during message access, attempt " + attempt);
            }
            
            return Future.succeededFuture();
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should have attempted processing 3 times");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("Exception during message access test completed successfully");
    }

    @Test
    void testInterruptedExceptionHandling(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing InterruptedException Handling ===");
        
        String testMessage = "Message that gets interrupted";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint retryCheckpoint = testContext.checkpoint(3);

        producer.send(testMessage).onFailure(testContext::failNow);

        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} with interruption", attempt);
            retryCheckpoint.flag();
            
            // Simulate interrupted exception
            Thread.currentThread().interrupt();
            throw new RuntimeException("INTENTIONAL FAILURE: Thread interrupted, attempt " + attempt, 
                new InterruptedException("Simulated interruption"));
        });

        assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS), "Should have attempted processing 3 times");
        assertEquals(3, attemptCount.get(), "Should have made exactly 3 processing attempts");
        
        logger.info("InterruptedException handling test completed successfully");
    }

    @Test
    void testOutOfMemoryErrorHandling(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing OutOfMemoryError Simulation ===");
        
        String testMessage = "Message that simulates OOM";
        AtomicInteger attemptCount = new AtomicInteger(0);
        Checkpoint errorCheckpoint = testContext.checkpoint();

        producer.send(testMessage).onFailure(testContext::failNow);

        // Set up consumer that simulates OOM
        consumer.subscribe(message -> {
            int attempt = attemptCount.incrementAndGet();
            logger.info("INTENTIONAL FAILURE: Processing attempt {} simulating OOM", attempt);
            errorCheckpoint.flag();
            
            // Simulate OOM by throwing it directly (safer than actually causing OOM)
            throw new OutOfMemoryError("INTENTIONAL FAILURE: Simulated OOM, attempt " + attempt);
        });

        assertTrue(testContext.awaitCompletion(10, TimeUnit.SECONDS), "Should have attempted processing and handled OOM simulation");
        assertTrue(attemptCount.get() >= 1, "Should have made at least 1 processing attempt");
        
        logger.info("OutOfMemoryError simulation test completed successfully");
    }
}


