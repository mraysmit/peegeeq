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

import dev.mars.peegeeq.api.messaging.ConsumerGroup;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.test.categories.TestCategories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;

/**
 * Tests for consumer group functionality in the outbox pattern.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class OutboxConsumerGroupTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private OutboxFactory outboxFactory;
    private MessageProducer<String> producer;
    private ConsumerGroup<String> consumerGroup;
    private String testTopic;
    private String testGroupName;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize schema first
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);

        // Use unique topic and group name for each test to avoid interference
        testTopic = "group-test-topic-" + UUID.randomUUID().toString().substring(0, 8);
        testGroupName = "test-group-" + UUID.randomUUID().toString().substring(0, 8);
        
        // Set up database connection
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());

        // Create and start manager
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("group-test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory and components using PgDatabaseService
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        outboxFactory = new OutboxFactory(databaseService, config);
        producer = outboxFactory.createProducer(testTopic, String.class);
        consumerGroup = outboxFactory.createConsumerGroup(testGroupName, testTopic, String.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumerGroup != null) {
            consumerGroup.close();
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
    }

    @Test
    void testBasicConsumerGroup() throws Exception {
        int messageCount = 6;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        Set<String> consumerIds = ConcurrentHashMap.newKeySet();

        // Add multiple consumers to the group
        consumerGroup.addConsumer("consumer-1", message -> {
            consumerIds.add("consumer-1");
            int count = processedCount.incrementAndGet();
            System.out.println("Consumer-1 processed message " + count + ": " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.addConsumer("consumer-2", message -> {
            consumerIds.add("consumer-2");
            int count = processedCount.incrementAndGet();
            System.out.println("Consumer-2 processed message " + count + ": " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.addConsumer("consumer-3", message -> {
            consumerIds.add("consumer-3");
            int count = processedCount.incrementAndGet();
            System.out.println("Consumer-3 processed message " + count + ": " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Start the consumer group
        consumerGroup.start();

        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Group test message " + i).get(5, TimeUnit.SECONDS);
            System.out.println("Sent message " + i);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
            "All messages should be processed by consumer group within timeout");
        assertEquals(messageCount, processedCount.get(), 
            "Should process all messages");

        System.out.println("Consumer group test completed:");
        System.out.println("  - Messages processed: " + processedCount.get());
        System.out.println("  - Active consumers: " + consumerIds);

        // Verify that multiple consumers were used (load balancing)
        assertTrue(consumerIds.size() > 1, 
            "Should use multiple consumers for load balancing, used: " + consumerIds);
    }

    @Test
    void testConsumerGroupWithFilters() throws Exception {
        int messageCount = 6;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger usProcessedCount = new AtomicInteger(0);
        AtomicInteger euProcessedCount = new AtomicInteger(0);

        // Add region-specific consumers with filters
        consumerGroup.addConsumer("us-consumer", 
            message -> {
                int count = usProcessedCount.incrementAndGet();
                System.out.println("US Consumer processed message " + count + ": " + message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            },
            message -> "US".equals(message.getHeaders().get("region"))
        );

        consumerGroup.addConsumer("eu-consumer", 
            message -> {
                int count = euProcessedCount.incrementAndGet();
                System.out.println("EU Consumer processed message " + count + ": " + message.getPayload());
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            },
            message -> "EU".equals(message.getHeaders().get("region"))
        );

        // Start the consumer group
        consumerGroup.start();

        // Send messages to different regions
        Map<String, String> usHeaders = Map.of("region", "US");
        Map<String, String> euHeaders = Map.of("region", "EU");

        for (int i = 0; i < 3; i++) {
            producer.send("US message " + i, usHeaders).get(5, TimeUnit.SECONDS);
            producer.send("EU message " + i, euHeaders).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
            "All filtered messages should be processed within timeout");

        System.out.println("Filtered consumer group test completed:");
        System.out.println("  - US messages processed: " + usProcessedCount.get());
        System.out.println("  - EU messages processed: " + euProcessedCount.get());

        // Verify correct filtering
        assertEquals(3, usProcessedCount.get(), "Should process 3 US messages");
        assertEquals(3, euProcessedCount.get(), "Should process 3 EU messages");
    }

    @Test
    void testConsumerGroupLoadBalancing() throws Exception {
        int messageCount = 12;
        CountDownLatch latch = new CountDownLatch(messageCount);
        Map<String, AtomicInteger> consumerCounts = new ConcurrentHashMap<>();
        
        // Initialize counters
        consumerCounts.put("consumer-1", new AtomicInteger(0));
        consumerCounts.put("consumer-2", new AtomicInteger(0));
        consumerCounts.put("consumer-3", new AtomicInteger(0));

        // Add consumers that track their message counts
        consumerGroup.addConsumer("consumer-1", message -> {
            int count = consumerCounts.get("consumer-1").incrementAndGet();
            System.out.println("Consumer-1 processed message " + count + ": " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.addConsumer("consumer-2", message -> {
            int count = consumerCounts.get("consumer-2").incrementAndGet();
            System.out.println("Consumer-2 processed message " + count + ": " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.addConsumer("consumer-3", message -> {
            int count = consumerCounts.get("consumer-3").incrementAndGet();
            System.out.println("Consumer-3 processed message " + count + ": " + message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Start the consumer group
        consumerGroup.start();

        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Load balance test message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(45, TimeUnit.SECONDS), 
            "All messages should be processed within timeout");

        System.out.println("Load balancing test completed:");
        consumerCounts.forEach((consumerId, count) -> {
            System.out.println("  - " + consumerId + " processed: " + count.get() + " messages");
        });

        // Verify load balancing - each consumer should process at least one message
        consumerCounts.forEach((consumerId, count) -> {
            assertTrue(count.get() > 0, 
                "Consumer " + consumerId + " should process at least one message");
        });

        // Verify total message count
        int totalProcessed = consumerCounts.values().stream()
            .mapToInt(AtomicInteger::get)
            .sum();
        assertEquals(messageCount, totalProcessed, 
            "Total processed messages should equal sent messages");
    }

    @Test
    void testConsumerGroupDynamicScaling() throws Exception {
        int initialMessageCount = 3;
        CountDownLatch initialLatch = new CountDownLatch(initialMessageCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicReference<CountDownLatch> currentLatch = new AtomicReference<>(initialLatch);

        // Start with one consumer
        consumerGroup.addConsumer("initial-consumer", message -> {
            int count = processedCount.incrementAndGet();
            System.out.println("Initial consumer processed message " + count + ": " + message.getPayload());
            currentLatch.get().countDown(); // Use the current latch
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.start();

        // Send initial messages
        for (int i = 0; i < initialMessageCount; i++) {
            producer.send("Initial message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for initial processing
        assertTrue(initialLatch.await(15, TimeUnit.SECONDS),
            "Initial messages should be processed");

        // Add more consumers dynamically
        int additionalMessageCount = 6;
        CountDownLatch additionalLatch = new CountDownLatch(additionalMessageCount);
        currentLatch.set(additionalLatch); // Switch to additional latch
        Set<String> activeConsumers = ConcurrentHashMap.newKeySet();

        consumerGroup.addConsumer("additional-consumer-1", message -> {
            activeConsumers.add("additional-consumer-1");
            int count = processedCount.incrementAndGet();
            System.out.println("Additional consumer 1 processed message " + count + ": " + message.getPayload());
            additionalLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        consumerGroup.addConsumer("additional-consumer-2", message -> {
            activeConsumers.add("additional-consumer-2");
            int count = processedCount.incrementAndGet();
            System.out.println("Additional consumer 2 processed message " + count + ": " + message.getPayload());
            additionalLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send additional messages
        for (int i = 0; i < additionalMessageCount; i++) {
            producer.send("Additional message " + i).get(5, TimeUnit.SECONDS);
        }

        // Wait for additional processing
        assertTrue(additionalLatch.await(20, TimeUnit.SECONDS),
            "Additional messages should be processed");

        System.out.println("Dynamic scaling test completed:");
        System.out.println("  - Total messages processed: " + processedCount.get());
        System.out.println("  - Active additional consumers: " + activeConsumers);

        assertEquals(initialMessageCount + additionalMessageCount, processedCount.get(),
            "Should process all messages");
        assertTrue(activeConsumers.size() > 0,
            "Should have active additional consumers");
    }
}
