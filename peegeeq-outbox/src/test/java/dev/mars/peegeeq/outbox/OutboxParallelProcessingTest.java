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

import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parallel processing capabilities in the outbox pattern.
 */
@Testcontainers
public class OutboxParallelProcessingTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private MessageConsumer<String> consumer;
    private String testTopic;

    @BeforeEach
    void setUp() throws Exception {
        // Use unique topic for each test to avoid interference
        testTopic = "parallel-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Configure parallel processing
        Properties parallelProps = new Properties();
        parallelProps.setProperty("peegeeq.consumer.threads", "4");
        parallelProps.setProperty("peegeeq.queue.batch-size", "5");
        parallelProps.setProperty("peegeeq.queue.polling-interval", "PT0.1S");
        
        // Apply the properties
        parallelProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("parallel-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components with configuration
        DatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumer = outboxFactory.createConsumer(testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (outboxFactory != null) {
            outboxFactory.close();
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
        System.clearProperty("peegeeq.consumer.threads");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.queue.polling-interval");
    }

    @Test
    void testParallelConsumerProcessing() throws Exception {
        int messageCount = 12;
        CountDownLatch latch = new CountDownLatch(messageCount);
        Set<String> processingThreads = ConcurrentHashMap.newKeySet();
        AtomicInteger processedCount = new AtomicInteger(0);

        consumer.subscribe(message -> {
            // Capture which thread is processing this message
            String threadName = Thread.currentThread().getName();
            processingThreads.add(threadName);
            
            int count = processedCount.incrementAndGet();
            System.out.println("Processing message " + count + " on thread: " + threadName + " - " + message.getPayload());
            
            // Simulate processing time to ensure parallel execution
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            System.out.println("Completed message " + count + " on thread: " + threadName);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send all messages first
        System.out.println("Sending " + messageCount + " messages...");
        for (int i = 0; i < messageCount; i++) {
            producer.send("Parallel message " + i).get(2, TimeUnit.SECONDS);
            System.out.println("Sent message " + i);
        }
        System.out.println("All messages sent, waiting for processing...");

        // Wait for all messages to be processed
        assertTrue(latch.await(60, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        assertEquals(messageCount, processedCount.get(), 
            "Should process all messages");

        System.out.println("Final thread usage summary:");
        System.out.println("   - Messages processed: " + processedCount.get());
        System.out.println("   - Processing threads used: " + processingThreads.size());
        System.out.println("   - Thread names: " + processingThreads);

        // Verify that multiple threads were used for processing
        assertTrue(processingThreads.size() > 1, 
            "Should use multiple processing threads, but only used: " + processingThreads);

        // Verify thread names contain the expected pattern
        boolean hasOutboxProcessorThreads = processingThreads.stream()
            .anyMatch(name -> name.contains("outbox-processor"));
        assertTrue(hasOutboxProcessorThreads, 
            "Should have outbox-processor threads, found: " + processingThreads);

        System.out.println("✅ Parallel processing test completed successfully!");
    }

    @Test
    void testBatchProcessing() throws Exception {
        int messageCount = 20;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        Set<String> processingThreads = ConcurrentHashMap.newKeySet();

        consumer.subscribe(message -> {
            String threadName = Thread.currentThread().getName();
            processingThreads.add(threadName);
            
            int count = processedCount.incrementAndGet();
            System.out.println("Batch processing message " + count + " on thread: " + threadName);
            
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send messages in rapid succession to test batch processing
        System.out.println("Sending " + messageCount + " messages for batch processing...");
        for (int i = 0; i < messageCount; i++) {
            producer.send("Batch message " + i).get(1, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
            "All batch messages should be processed within timeout");
        assertEquals(messageCount, processedCount.get(), 
            "Should process all batch messages");

        System.out.println("Batch processing completed:");
        System.out.println("   - Messages processed: " + processedCount.get());
        System.out.println("   - Processing threads used: " + processingThreads.size());
    }

    @Test
    void testConcurrentProducers() throws Exception {
        int producerCount = 3;
        int messagesPerProducer = 5;
        int totalMessages = producerCount * messagesPerProducer;
        
        CountDownLatch latch = new CountDownLatch(totalMessages);
        AtomicInteger processedCount = new AtomicInteger(0);
        Set<String> processingThreads = ConcurrentHashMap.newKeySet();

        consumer.subscribe(message -> {
            String threadName = Thread.currentThread().getName();
            processingThreads.add(threadName);
            
            int count = processedCount.incrementAndGet();
            System.out.println("Concurrent processing message " + count + " on thread: " + threadName);
            
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Create multiple producers sending concurrently
        CompletableFuture<Void>[] producerFutures = new CompletableFuture[producerCount];
        
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producerFutures[p] = CompletableFuture.runAsync(() -> {
                try {
                    MessageProducer<String> concurrentProducer = outboxFactory.createProducer(testTopic, String.class);
                    for (int m = 0; m < messagesPerProducer; m++) {
                        String message = "Producer-" + producerId + "-Message-" + m;
                        concurrentProducer.send(message).get(5, TimeUnit.SECONDS);
                        System.out.println("Producer " + producerId + " sent message " + m);
                    }
                    concurrentProducer.close();
                } catch (Exception e) {
                    throw new RuntimeException("Producer " + producerId + " failed", e);
                }
            });
        }

        // Wait for all producers to complete
        CompletableFuture.allOf(producerFutures).get(30, TimeUnit.SECONDS);
        System.out.println("All concurrent producers completed");

        // Wait for all messages to be processed
        assertTrue(latch.await(45, TimeUnit.SECONDS), 
            "All concurrent messages should be processed within timeout");
        assertEquals(totalMessages, processedCount.get(), 
            "Should process all concurrent messages");

        System.out.println("Concurrent producer test completed:");
        System.out.println("   - Total messages processed: " + processedCount.get());
        System.out.println("   - Processing threads used: " + processingThreads.size());
    }
}
