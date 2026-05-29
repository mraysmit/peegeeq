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

package dev.mars.peegeeq.examples.nativequeue;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.examples.shared.SharedTestContainers;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import dev.mars.peegeeq.test.config.PeeGeeQTestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.core.Future;

import java.util.concurrent.TimeUnit;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Simple test to isolate native queue issues.
 * This test focuses on the most basic scenario: send one message, receive one message.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
@ExtendWith(VertxExtension.class)
public class SimpleNativeQueueTest {

    private static final Logger logger = LoggerFactory.getLogger(SimpleNativeQueueTest.class);

    static PostgreSQLContainer postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        logger.info("Setting up: configuring database and starting PeeGeeQManager");
        logger.info("=== Setting up SimpleNativeQueueTest ===");

        Properties testProps = PeeGeeQTestConfig.builder().from(postgres).build();

        logger.info(" Initializing database schema for simple native queue test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer (ALL components)");

        PeeGeeQConfiguration config = new PeeGeeQConfiguration("default", testProps);
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());

        manager.start()
            .onSuccess(v -> {
                var databaseService = new PgDatabaseService(manager);
                QueueFactoryProvider provider = new PgQueueFactoryProvider();
                PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
                nativeFactory = provider.createFactory("native", databaseService);
                logger.info("Simple native queue test setup completed successfully");
                ctx.completeNow();
            })
            .onFailure(ctx::failNow);
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext ctx) {
        logger.info("Tearing down: closing resources and manager");
        logger.info("=== Tearing down SimpleNativeQueueTest ===");

        if (nativeFactory != null) {
            try {
                nativeFactory.close();
                logger.info("Native factory closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing native factory: {}", e.getMessage());
            }
        }

        if (manager == null) {
            logger.info("Simple native queue test teardown completed");
            ctx.completeNow();
            return;
        }
        logger.info("Closing PeeGeeQ manager...");
        Future<Void> closeChain = manager.closeReactive()
            .onSuccess(v -> logger.info("PeeGeeQ manager closed successfully"))
            .onFailure(err -> logger.error("Error during manager cleanup", err));
        closeChain.onSuccess(v -> { logger.info("Simple native queue test teardown completed"); ctx.completeNow(); });
        closeChain.onFailure(err -> ctx.completeNow());
    }

    @Test
    void testSingleMessageSendAndReceive(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Single Message Send and Receive ===");

        // Create producer and consumer
        MessageProducer<String> producer = nativeFactory.createProducer("simple-test", String.class);
        MessageConsumer<String> consumer = nativeFactory.createConsumer("simple-test", String.class);

        // Set up message reception
        Checkpoint checkpoint = testContext.checkpoint(1);
        AtomicInteger processedCount = new AtomicInteger();
        String testMessage = "Hello Simple Test";

        logger.info("Setting up consumer subscription...");
        // Chain send off the subscribe Future  the subscribe Future resolves once
        // the consumer is registered and ready to receive notifications.
        consumer.subscribe(message -> {
            logger.info("RECEIVED MESSAGE: {}", message.getPayload());
            processedCount.incrementAndGet();
            checkpoint.flag();
            return Future.succeededFuture();
        }).onSuccess(ready -> {
            logger.info("Sending message: {}", testMessage);
            producer.send(testMessage).onFailure(testContext::failNow);
        }).onFailure(testContext::failNow);

        // Wait for message to be processed
        logger.info("Waiting for message to be received...");
        Assertions.assertTrue(testContext.awaitCompletion(15, TimeUnit.SECONDS),
            "Message was not received within timeout");

        // Verify results
        logger.info("Processed count: {}", processedCount.get());
        Assertions.assertEquals(1, processedCount.get(), "Exactly one message should be processed");

        // Clean up with debug logging
        logger.info("Closing producer...");
        producer.close();
        logger.info("Producer closed");

        logger.info("Closing consumer...");
        consumer.close();
        logger.info("Consumer closed");

        logger.info("Single message test passed");
    }

    @Test
    void testConcurrentMessageProcessing(VertxTestContext testContext) throws Exception {
        logger.info("=== Testing Concurrent Message Processing ===");

        // Create producer and consumer
        MessageProducer<String> producer = nativeFactory.createProducer("concurrent-test", String.class);
        MessageConsumer<String> consumer = nativeFactory.createConsumer("concurrent-test", String.class);

        int messageCount = 10; // Reduce count to isolate concurrency issues
        Checkpoint checkpoint = testContext.checkpoint(messageCount);
        AtomicInteger processedCount = new AtomicInteger();
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        logger.info("Setting up consumer subscription for {} messages...", messageCount);
        // Chain sends off subscribe  once the subscribe Future resolves the consumer
        // is registered and ready to receive notifications.
        consumer.subscribe(message -> {
            logger.info("RECEIVED CONCURRENT MESSAGE: {}", message.getPayload());
            receivedMessages.add(message.getPayload());
            processedCount.incrementAndGet();
            checkpoint.flag();
            return Future.succeededFuture();
        }).onSuccess(ready -> {
            logger.info("Sending {} messages concurrently...", messageCount);
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < messageCount; i++) {
                final int messageId = i;
                String message = "Concurrent message " + messageId;
                logger.info("Sending message {}: {}", messageId, message);
                futures.add(producer.send(message));
            }
            Future.all(futures)
                .onSuccess(v -> logger.info("All sends completed"))
                .onFailure(testContext::failNow);
        }).onFailure(testContext::failNow);

        // Wait for all messages to be processed
        logger.info("Waiting for all {} messages to be received...", messageCount);
        boolean allReceived = testContext.awaitCompletion(30, TimeUnit.SECONDS);

        // Verify results
        logger.info("All received: {}, Processed count: {}, Expected: {}", allReceived, processedCount.get(), messageCount);
        logger.info("Received messages: {}", receivedMessages);

        Assertions.assertTrue(allReceived, "All messages should be received within timeout");
        Assertions.assertEquals(messageCount, processedCount.get(), "All messages should be processed");
        Assertions.assertEquals(messageCount, receivedMessages.size(), "All messages should be in received list");

        // Clean up
        producer.close();
        consumer.close();

        logger.info("Concurrent message test passed");
    }
}


