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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for the outbox pattern implementation.
 * These tests are disabled by default and can be enabled with -Dpeegeeq.performance.tests=true
 */
@Testcontainers
@EnabledIfSystemProperty(named = "peegeeq.performance.tests", matches = "true")
public class OutboxPerformanceTest {

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
        // Initialize schema first
        TestSchemaInitializer.initializeSchema(postgres);

        // Use unique topic for each test to avoid interference
        testTopic = "perf-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Configure for performance
        Properties perfProps = new Properties();
        perfProps.setProperty("peegeeq.consumer.threads", "8");
        perfProps.setProperty("peegeeq.queue.batch-size", "50");
        perfProps.setProperty("peegeeq.queue.polling-interval", "PT0.1S");
        perfProps.setProperty("peegeeq.connection.pool.size", "20");
        
        // Apply the properties
        perfProps.forEach((key, value) -> System.setProperty(key.toString(), value.toString()));

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("perf-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components
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
        System.clearProperty("peegeeq.connection.pool.size");
    }

    @Test
    void testThroughputPerformance() throws Exception {
        int messageCount = 1000;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);

        // Set up consumer with timing
        consumer.subscribe(message -> {
            long startTime = System.nanoTime();
            
            int count = processedCount.incrementAndGet();
            if (count % 100 == 0) {
                System.out.println("Processed " + count + " messages...");
            }
            
            long endTime = System.nanoTime();
            totalProcessingTime.addAndGet(endTime - startTime);
            
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        System.out.println("Starting throughput test with " + messageCount + " messages...");
        Instant startTime = Instant.now();

        // Send all messages as fast as possible
        CompletableFuture<Void>[] sendFutures = new CompletableFuture[messageCount];
        for (int i = 0; i < messageCount; i++) {
            sendFutures[i] = producer.send("Performance test message " + i);
        }

        // Wait for all sends to complete
        CompletableFuture.allOf(sendFutures).get(60, TimeUnit.SECONDS);
        Instant sendCompleteTime = Instant.now();

        // Wait for all messages to be processed
        assertTrue(latch.await(120, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");
        Instant processCompleteTime = Instant.now();

        // Calculate performance metrics
        Duration sendDuration = Duration.between(startTime, sendCompleteTime);
        Duration totalDuration = Duration.between(startTime, processCompleteTime);
        Duration processingDuration = Duration.between(sendCompleteTime, processCompleteTime);

        double sendThroughput = messageCount / (sendDuration.toMillis() / 1000.0);
        double totalThroughput = messageCount / (totalDuration.toMillis() / 1000.0);
        double avgProcessingTimeNs = totalProcessingTime.get() / (double) messageCount;

        System.out.println("\n=== THROUGHPUT PERFORMANCE RESULTS ===");
        System.out.println("Messages: " + messageCount);
        System.out.println("Send duration: " + sendDuration.toMillis() + "ms");
        System.out.println("Processing duration: " + processingDuration.toMillis() + "ms");
        System.out.println("Total duration: " + totalDuration.toMillis() + "ms");
        System.out.println("Send throughput: " + String.format("%.2f", sendThroughput) + " msg/sec");
        System.out.println("Total throughput: " + String.format("%.2f", totalThroughput) + " msg/sec");
        System.out.println("Avg processing time: " + String.format("%.2f", avgProcessingTimeNs / 1_000_000) + "ms");

        // Verify all messages were processed
        assertEquals(messageCount, processedCount.get(), "Should process all messages");

        // Performance assertions (adjust based on expected performance)
        assertTrue(sendThroughput > 100, "Send throughput should be > 100 msg/sec, was: " + sendThroughput);
        assertTrue(totalThroughput > 50, "Total throughput should be > 50 msg/sec, was: " + totalThroughput);
    }

    @Test
    void testLatencyPerformance() throws Exception {
        int messageCount = 100;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxLatency = new AtomicLong(0);

        // Set up consumer with latency measurement
        consumer.subscribe(message -> {
            long receiveTime = System.nanoTime();
            long sendTime = Long.parseLong(message.getHeaders().get("sendTime"));
            long latency = receiveTime - sendTime;
            
            totalLatency.addAndGet(latency);
            minLatency.updateAndGet(current -> Math.min(current, latency));
            maxLatency.updateAndGet(current -> Math.max(current, latency));
            
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        System.out.println("Starting latency test with " + messageCount + " messages...");

        // Send messages with timestamps
        for (int i = 0; i < messageCount; i++) {
            long sendTime = System.nanoTime();
            producer.send("Latency test message " + i, 
                Map.of("sendTime", String.valueOf(sendTime)))
                .get(5, TimeUnit.SECONDS);
            
            // Small delay between sends to measure individual latencies
            Thread.sleep(10);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(60, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");

        // Calculate latency metrics
        double avgLatencyMs = (totalLatency.get() / (double) messageCount) / 1_000_000;
        double minLatencyMs = minLatency.get() / 1_000_000.0;
        double maxLatencyMs = maxLatency.get() / 1_000_000.0;

        System.out.println("\n=== LATENCY PERFORMANCE RESULTS ===");
        System.out.println("Messages: " + messageCount);
        System.out.println("Average latency: " + String.format("%.2f", avgLatencyMs) + "ms");
        System.out.println("Min latency: " + String.format("%.2f", minLatencyMs) + "ms");
        System.out.println("Max latency: " + String.format("%.2f", maxLatencyMs) + "ms");

        // Performance assertions (adjust based on expected performance)
        assertTrue(avgLatencyMs < 1000, "Average latency should be < 1000ms, was: " + avgLatencyMs);
        assertTrue(minLatencyMs < 500, "Min latency should be < 500ms, was: " + minLatencyMs);
    }

    @Test
    void testConcurrentProducerPerformance() throws Exception {
        int producerCount = 5;
        int messagesPerProducer = 200;
        int totalMessages = producerCount * messagesPerProducer;
        
        CountDownLatch latch = new CountDownLatch(totalMessages);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Set up consumer
        consumer.subscribe(message -> {
            int count = processedCount.incrementAndGet();
            if (count % 100 == 0) {
                System.out.println("Processed " + count + " concurrent messages...");
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        System.out.println("Starting concurrent producer test: " + producerCount + 
            " producers, " + messagesPerProducer + " messages each...");
        
        Instant startTime = Instant.now();

        // Create and run concurrent producers
        CompletableFuture<Void>[] producerFutures = new CompletableFuture[producerCount];
        
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producerFutures[p] = CompletableFuture.runAsync(() -> {
                try {
                    MessageProducer<String> concurrentProducer = 
                        outboxFactory.createProducer(testTopic, String.class);
                    
                    for (int m = 0; m < messagesPerProducer; m++) {
                        concurrentProducer.send("Concurrent-P" + producerId + "-M" + m)
                            .get(10, TimeUnit.SECONDS);
                    }
                    
                    concurrentProducer.close();
                } catch (Exception e) {
                    throw new RuntimeException("Producer " + producerId + " failed", e);
                }
            });
        }

        // Wait for all producers to complete
        CompletableFuture.allOf(producerFutures).get(120, TimeUnit.SECONDS);
        Instant sendCompleteTime = Instant.now();

        // Wait for all messages to be processed
        assertTrue(latch.await(180, TimeUnit.SECONDS), 
            "All concurrent messages should be processed within timeout");
        Instant processCompleteTime = Instant.now();

        // Calculate performance metrics
        Duration sendDuration = Duration.between(startTime, sendCompleteTime);
        Duration totalDuration = Duration.between(startTime, processCompleteTime);

        double sendThroughput = totalMessages / (sendDuration.toMillis() / 1000.0);
        double totalThroughput = totalMessages / (totalDuration.toMillis() / 1000.0);

        System.out.println("\n=== CONCURRENT PRODUCER PERFORMANCE RESULTS ===");
        System.out.println("Producers: " + producerCount);
        System.out.println("Messages per producer: " + messagesPerProducer);
        System.out.println("Total messages: " + totalMessages);
        System.out.println("Send duration: " + sendDuration.toMillis() + "ms");
        System.out.println("Total duration: " + totalDuration.toMillis() + "ms");
        System.out.println("Send throughput: " + String.format("%.2f", sendThroughput) + " msg/sec");
        System.out.println("Total throughput: " + String.format("%.2f", totalThroughput) + " msg/sec");

        // Verify all messages were processed
        assertEquals(totalMessages, processedCount.get(), "Should process all concurrent messages");

        // Performance assertions
        assertTrue(sendThroughput > 50, "Concurrent send throughput should be > 50 msg/sec, was: " + sendThroughput);
        assertTrue(totalThroughput > 25, "Concurrent total throughput should be > 25 msg/sec, was: " + totalThroughput);
    }
}
