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

package dev.mars.peegeeq.examples.patterns;

import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple working test to validate the correct imports and basic PeeGeeQ functionality.
 * This test follows the exact pattern from SimpleNativeQueueTest.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SimpleWorkingTest {

    private static final Logger logger = LoggerFactory.getLogger(SimpleWorkingTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_pass");

    private PeeGeeQManager manager;
    private QueueFactory queueFactory;

    @BeforeEach
    void setUp() {
        logger.info("=== Setting up SimpleWorkingTest ===");
        
        // Set system properties for database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create configuration and manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("simple-working-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();
        
        // Create native factory
        var databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);
        
        queueFactory = provider.createFactory("native", databaseService);
        
        logger.info("Simple working test setup completed successfully");
    }

    @AfterEach
    void tearDown() {
        logger.info("=== Tearing down SimpleWorkingTest ===");
        
        if (queueFactory != null) {
            try {
                queueFactory.close();
            } catch (Exception e) {
                logger.warn("Error closing queue factory: {}", e.getMessage());
            }
        }
        
        if (manager != null) {
            manager.close();
        }
        
        // Clear system properties
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        
        logger.info("Simple working test teardown completed");
    }

    @Test
    @Order(1)
    void testBasicMessageSendAndReceive() throws Exception {
        System.err.println("=== TEST METHOD STARTED: testBasicMessageSendAndReceive ===");
        System.err.flush();
        logger.info("=== Testing basic message send and receive ===");
        
        String queueName = "simple-test-queue";
        String testMessage = "Hello, PeeGeeQ!";
        
        // Create producer and consumer
        MessageProducer<String> producer = queueFactory.createProducer(queueName, String.class);
        MessageConsumer<String> consumer = queueFactory.createConsumer(queueName, String.class);
        
        // Set up message reception
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        
        consumer.subscribe(message -> {
            logger.info("Received message: {}", message.getPayload());
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Give consumer time to set up
        Thread.sleep(2000);
        
        // Send message
        logger.info("Sending message: {}", testMessage);
        producer.send(testMessage).get(5, TimeUnit.SECONDS);
        
        // Wait for message reception
        boolean received = latch.await(10, TimeUnit.SECONDS);
        
        // Close consumer
        consumer.close();
        
        // Verify results
        Assertions.assertTrue(received, "Message should have been received within timeout");
        Assertions.assertEquals(1, receivedCount.get(), "Should have received exactly one message");
        
        logger.info("✅ Basic message send and receive test completed successfully");
    }

    @Test
    @Order(2)
    void testMultipleMessages() throws Exception {
        logger.info("=== Testing multiple message send and receive ===");
        
        String queueName = "multi-test-queue";
        int messageCount = 5;
        
        // Create producer and consumer
        MessageProducer<String> producer = queueFactory.createProducer(queueName, String.class);
        MessageConsumer<String> consumer = queueFactory.createConsumer(queueName, String.class);
        
        // Set up message reception
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger receivedCount = new AtomicInteger(0);
        
        consumer.subscribe(message -> {
            logger.info("Received message: {}", message.getPayload());
            receivedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Give consumer time to set up
        Thread.sleep(2000);
        
        // Send multiple messages
        for (int i = 1; i <= messageCount; i++) {
            String message = "Message " + i;
            logger.info("Sending message: {}", message);
            producer.send(message).get(5, TimeUnit.SECONDS);
        }
        
        // Wait for all messages to be received
        boolean allReceived = latch.await(15, TimeUnit.SECONDS);
        
        // Close consumer
        consumer.close();
        
        // Verify results
        Assertions.assertTrue(allReceived, "All messages should have been received within timeout");
        Assertions.assertEquals(messageCount, receivedCount.get(), 
            "Should have received exactly " + messageCount + " messages");
        
        logger.info("✅ Multiple message test completed successfully");
    }

    @Test
    @Order(3)
    void testImportsAndSetupWorking() {
        logger.info("=== Testing that imports and setup are working correctly ===");
        
        // Verify that all the key components are properly initialized
        Assertions.assertNotNull(manager, "PeeGeeQManager should be initialized");
        Assertions.assertNotNull(queueFactory, "QueueFactory should be initialized");
        Assertions.assertTrue(postgres.isRunning(), "PostgreSQL container should be running");
        
        // Verify system properties are set
        Assertions.assertNotNull(System.getProperty("peegeeq.database.host"), 
            "Database host property should be set");
        Assertions.assertNotNull(System.getProperty("peegeeq.database.port"), 
            "Database port property should be set");
        
        logger.info("✅ Imports and setup verification completed successfully");
        logger.info("🎉 All tests are working with correct imports and patterns!");
    }
}
