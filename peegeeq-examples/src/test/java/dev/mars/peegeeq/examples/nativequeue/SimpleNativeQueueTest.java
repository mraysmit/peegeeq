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
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple test to isolate native queue issues.
 * This test focuses on the most basic scenario: send one message, receive one message.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SimpleNativeQueueTest {

    private static final Logger logger = LoggerFactory.getLogger(SimpleNativeQueueTest.class);

    static PostgreSQLContainer<?> postgres = SharedTestContainers.getSharedPostgreSQLContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        SharedTestContainers.configureSharedProperties(registry);
    }

    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;

    /**
     * Configure system properties for TestContainers PostgreSQL connection
     */
    private void configureSystemPropertiesForContainer() {
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
    }

    @BeforeEach
    void setUp() throws Exception {
        logger.info("=== Setting up SimpleNativeQueueTest ===");

        // Configure system properties for TestContainers
        configureSystemPropertiesForContainer();

        // Initialize database schema for simple native queue test
        logger.info("🔧 Initializing database schema for simple native queue test");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.ALL);
        logger.info("✅ Database schema initialized successfully using centralized schema initializer (ALL components)");
        
        // Create manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("simple-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        
        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        nativeFactory = provider.createFactory("native", databaseService);
        
        logger.info("Simple native queue test setup completed successfully");
    }

    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down SimpleNativeQueueTest ===");

        if (nativeFactory != null) {
            try {
                nativeFactory.close();
                logger.info("Native factory closed successfully");
            } catch (Exception e) {
                logger.warn("Error closing native factory: {}", e.getMessage());
            }
        }

        if (manager != null) {
            try {
                logger.info("Closing PeeGeeQ manager...");
                manager.close();
                logger.info("PeeGeeQ manager closed successfully");

                // CRITICAL: Wait for all resources to be fully released
                // This prevents connection pool exhaustion in subsequent tests
                Thread.sleep(2000);
                logger.info("Resource cleanup wait completed");
            } catch (Exception e) {
                logger.error("Error during manager cleanup", e);
            }
        }

        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");

        logger.info("Simple native queue test teardown completed");
    }

    @Test
    @Order(1)
    void testSingleMessageSendAndReceive() throws Exception {
        logger.info("=== Testing Single Message Send and Receive ===");

        // Create producer and consumer
        MessageProducer<String> producer = nativeFactory.createProducer("simple-test", String.class);
        MessageConsumer<String> consumer = nativeFactory.createConsumer("simple-test", String.class);

        // Set up message reception
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger processedCount = new AtomicInteger();
        String testMessage = "Hello Simple Test";

        logger.info("Setting up consumer subscription...");
        consumer.subscribe(message -> {
            logger.info("✅ RECEIVED MESSAGE: {}", message.getPayload());
            processedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        logger.info("Consumer subscribed, waiting 2 seconds for setup...");
        Thread.sleep(2000); // Give consumer time to set up LISTEN

        // Send message
        logger.info("Sending message: {}", testMessage);
        producer.send(testMessage).get(10, TimeUnit.SECONDS);
        logger.info("✅ Message sent successfully");

        // Wait for message to be processed
        logger.info("Waiting for message to be received...");
        boolean messageReceived = latch.await(15, TimeUnit.SECONDS);

        // Verify results
        logger.info("Message received: {}, Processed count: {}", messageReceived, processedCount.get());

        Assertions.assertTrue(messageReceived, "Message should be received within timeout");
        Assertions.assertEquals(1, processedCount.get(), "Exactly one message should be processed");

        // Clean up with debug logging
        logger.info("Closing producer...");
        producer.close();
        logger.info("Producer closed");

        logger.info("Closing consumer...");
        consumer.close();
        logger.info("Consumer closed");

        logger.info("✅ Single message test passed");
    }

    @Test
    @Order(2)
    void testConcurrentMessageProcessing() throws Exception {
        logger.info("=== Testing Concurrent Message Processing ===");

        // Create producer and consumer
        MessageProducer<String> producer = nativeFactory.createProducer("concurrent-test", String.class);
        MessageConsumer<String> consumer = nativeFactory.createConsumer("concurrent-test", String.class);

        int messageCount = 10; // Reduce count to isolate concurrency issues
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger();
        List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());

        logger.info("Setting up consumer subscription for {} messages...", messageCount);
        consumer.subscribe(message -> {
            logger.info("✅ RECEIVED CONCURRENT MESSAGE: {}", message.getPayload());
            receivedMessages.add(message.getPayload());
            processedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        logger.info("Consumer subscribed, waiting 2 seconds for setup...");
        Thread.sleep(2000);

        // Send messages concurrently like the original failing test
        logger.info("Sending {} messages concurrently...", messageCount);
        ExecutorService executor = Executors.newFixedThreadPool(2); // Reduce thread pool size
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < messageCount; i++) {
            final int messageId = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String message = "Concurrent message " + messageId;
                    logger.info("Sending message {}: {}", messageId, message);
                    producer.send(message).get(5, TimeUnit.SECONDS);
                    logger.info("✅ Message {} sent successfully", messageId);
                } catch (Exception e) {
                    logger.error("❌ Failed to send message " + messageId, e);
                }
            }, executor);
            futures.add(future);
        }

        // Wait for all sends to complete
        logger.info("Waiting for all sends to complete...");
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        logger.info("✅ All sends completed");

        executor.shutdown();

        // Wait for all messages to be processed
        logger.info("Waiting for all {} messages to be received...", messageCount);
        boolean allReceived = latch.await(30, TimeUnit.SECONDS);

        // Debug: Check database state if not all messages received
        if (!allReceived) {
            logger.warn("Not all messages received - checking database state...");
            // Add a small delay to let any pending operations complete
            Thread.sleep(1000);
        }

        // Verify results
        logger.info("All received: {}, Processed count: {}, Expected: {}", allReceived, processedCount.get(), messageCount);
        logger.info("Received messages: {}", receivedMessages);

        Assertions.assertTrue(allReceived, "All messages should be received within timeout");
        Assertions.assertEquals(messageCount, processedCount.get(), "All messages should be processed");
        Assertions.assertEquals(messageCount, receivedMessages.size(), "All messages should be in received list");

        // Clean up
        producer.close();
        consumer.close();

        logger.info("✅ Concurrent message test passed");
    }
}
