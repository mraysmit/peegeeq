package dev.mars.peegeeq.examples;

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

import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryProvider;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive comparison tests between native and outbox queue implementations.
 * This test class validates that both implementations work correctly and highlights
 * their different characteristics and use cases.
 * 
 * Tests cover:
 * - Performance differences
 * - Reliability characteristics
 * - Feature availability
 * - Use case suitability
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-03
 * @version 1.0
 */
@Testcontainers
class NativeVsOutboxComparisonTest {
    private static final Logger logger = LoggerFactory.getLogger(NativeVsOutboxComparisonTest.class);
    
    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_comparison_test")
            .withUsername("peegeeq_test")
            .withPassword("peegeeq_test")
            .withSharedMemorySize(256 * 1024 * 1024L)
            .withReuse(false);
    
    private PeeGeeQManager manager;
    private QueueFactory nativeFactory;
    private QueueFactory outboxFactory;
    
    @BeforeEach
    void setUp() throws Exception {
        // Configure system properties for the container
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        
        // Initialize PeeGeeQ Manager
        manager = new PeeGeeQManager(new PeeGeeQConfiguration("development"), new SimpleMeterRegistry());
        manager.start();
        
        // Create both factory types
        DatabaseService databaseService = new PgDatabaseService(manager);
        QueueFactoryProvider provider = new PgQueueFactoryProvider();
        
        nativeFactory = provider.createFactory("native", databaseService);
        outboxFactory = provider.createFactory("outbox", databaseService);
        
        logger.info("Native vs Outbox comparison test setup completed successfully");
    }
    
    @AfterEach
    void tearDown() {
        if (nativeFactory != null) {
            try {
                nativeFactory.close();
            } catch (Exception e) {
                logger.error("Error closing native factory", e);
            }
        }
        if (outboxFactory != null) {
            try {
                outboxFactory.close();
            } catch (Exception e) {
                logger.error("Error closing outbox factory", e);
            }
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("Native vs Outbox comparison test teardown completed");
    }
    
    @Test
    void testImplementationTypeIdentification() {
        // Verify that both factories are properly identified
        assertEquals("native", nativeFactory.getImplementationType());
        assertEquals("outbox", outboxFactory.getImplementationType());
        
        // Verify they are different classes
        assertNotEquals(nativeFactory.getClass(), outboxFactory.getClass());
        
        logger.info("✅ Implementation type identification test passed");
    }
    
    @Test
    void testBasicFunctionalityComparison() throws Exception {
        // Test that both implementations provide basic messaging functionality
        String testMessage = "Comparison test message";
        
        // Test native implementation
        MessageProducer<String> nativeProducer = nativeFactory.createProducer("comparison-native", String.class);
        MessageConsumer<String> nativeConsumer = nativeFactory.createConsumer("comparison-native", String.class);
        
        CountDownLatch nativeLatch = new CountDownLatch(1);
        List<String> nativeMessages = new ArrayList<>();
        
        nativeConsumer.subscribe(message -> {
            nativeMessages.add(message.getPayload());
            nativeLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        nativeProducer.send(testMessage).get(5, TimeUnit.SECONDS);
        assertTrue(nativeLatch.await(10, TimeUnit.SECONDS));
        assertEquals(1, nativeMessages.size());
        assertEquals(testMessage, nativeMessages.get(0));
        
        // Test outbox implementation
        MessageProducer<String> outboxProducer = outboxFactory.createProducer("comparison-outbox", String.class);
        MessageConsumer<String> outboxConsumer = outboxFactory.createConsumer("comparison-outbox", String.class);
        
        CountDownLatch outboxLatch = new CountDownLatch(1);
        List<String> outboxMessages = new ArrayList<>();
        
        outboxConsumer.subscribe(message -> {
            outboxMessages.add(message.getPayload());
            outboxLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        outboxProducer.send(testMessage).get(5, TimeUnit.SECONDS);
        assertTrue(outboxLatch.await(10, TimeUnit.SECONDS));
        assertEquals(1, outboxMessages.size());
        assertEquals(testMessage, outboxMessages.get(0));
        
        // Cleanup
        nativeProducer.close();
        nativeConsumer.close();
        outboxProducer.close();
        outboxConsumer.close();
        
        logger.info("✅ Basic functionality comparison test passed");
    }
    
    @Test
    void testPerformanceCharacteristics() throws Exception {
        // Compare performance characteristics between native and outbox
        int messageCount = 10; // Reduced for more reliable testing
        String testMessage = "Performance test message";
        
        // Test native performance
        long nativeStartTime = System.currentTimeMillis();
        MessageProducer<String> nativeProducer = nativeFactory.createProducer("perf-native", String.class);
        MessageConsumer<String> nativeConsumer = nativeFactory.createConsumer("perf-native", String.class);
        
        CountDownLatch nativeLatch = new CountDownLatch(messageCount);
        AtomicLong nativeFirstReceiveTime = new AtomicLong();
        AtomicLong nativeLastReceiveTime = new AtomicLong();
        
        nativeConsumer.subscribe(message -> {
            if (nativeFirstReceiveTime.get() == 0) {
                nativeFirstReceiveTime.set(System.currentTimeMillis());
            }
            nativeLastReceiveTime.set(System.currentTimeMillis());
            nativeLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send messages to native with small delays
        for (int i = 0; i < messageCount; i++) {
            nativeProducer.send(testMessage + " " + i).get(5, TimeUnit.SECONDS);
            Thread.sleep(100); // Small delay between messages
        }
        
        assertTrue(nativeLatch.await(30, TimeUnit.SECONDS));
        long nativeEndTime = System.currentTimeMillis();
        long nativeTotalTime = nativeEndTime - nativeStartTime;
        long nativeFirstMessageLatency = nativeFirstReceiveTime.get() - nativeStartTime;
        
        // Test outbox performance
        long outboxStartTime = System.currentTimeMillis();
        MessageProducer<String> outboxProducer = outboxFactory.createProducer("perf-outbox", String.class);
        MessageConsumer<String> outboxConsumer = outboxFactory.createConsumer("perf-outbox", String.class);
        
        CountDownLatch outboxLatch = new CountDownLatch(messageCount);
        AtomicLong outboxFirstReceiveTime = new AtomicLong();
        AtomicLong outboxLastReceiveTime = new AtomicLong();
        
        outboxConsumer.subscribe(message -> {
            if (outboxFirstReceiveTime.get() == 0) {
                outboxFirstReceiveTime.set(System.currentTimeMillis());
            }
            outboxLastReceiveTime.set(System.currentTimeMillis());
            outboxLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send messages to outbox with small delays
        for (int i = 0; i < messageCount; i++) {
            outboxProducer.send(testMessage + " " + i).get(5, TimeUnit.SECONDS);
            Thread.sleep(100); // Small delay between messages
        }
        
        assertTrue(outboxLatch.await(30, TimeUnit.SECONDS));
        long outboxEndTime = System.currentTimeMillis();
        long outboxTotalTime = outboxEndTime - outboxStartTime;
        long outboxFirstMessageLatency = outboxFirstReceiveTime.get() - outboxStartTime;
        
        // Log performance results
        logger.info("Performance Comparison Results:");
        logger.info("Native - Total time: {}ms, First message latency: {}ms", nativeTotalTime, nativeFirstMessageLatency);
        logger.info("Outbox - Total time: {}ms, First message latency: {}ms", outboxTotalTime, outboxFirstMessageLatency);
        
        // Native should generally be faster for real-time scenarios
        // But both should complete within reasonable time
        assertTrue(nativeTotalTime < 30000, "Native should complete within 30 seconds");
        assertTrue(outboxTotalTime < 30000, "Outbox should complete within 30 seconds");
        
        // Cleanup
        nativeProducer.close();
        nativeConsumer.close();
        outboxProducer.close();
        outboxConsumer.close();
        
        logger.info("✅ Performance characteristics test passed");
    }
    
    @Test
    void testConsumerGroupComparison() throws Exception {
        // Compare consumer group functionality between implementations
        String topic = "consumer-group-comparison";
        
        // Test native consumer group
        ConsumerGroup<String> nativeGroup = nativeFactory.createConsumerGroup("native-group", topic, String.class);
        MessageProducer<String> nativeProducer = nativeFactory.createProducer(topic, String.class);
        
        CountDownLatch nativeLatch = new CountDownLatch(6);
        AtomicInteger nativeProcessed = new AtomicInteger();
        
        nativeGroup.addConsumer("native-member-1", message -> {
            nativeProcessed.incrementAndGet();
            nativeLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        nativeGroup.addConsumer("native-member-2", message -> {
            nativeProcessed.incrementAndGet();
            nativeLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        nativeGroup.start();
        
        // Send messages to native
        for (int i = 0; i < 6; i++) {
            nativeProducer.send("Native group message " + i).get(2, TimeUnit.SECONDS);
        }
        
        assertTrue(nativeLatch.await(15, TimeUnit.SECONDS));
        assertEquals(6, nativeProcessed.get());
        
        // Test outbox consumer group
        ConsumerGroup<String> outboxGroup = outboxFactory.createConsumerGroup("outbox-group", topic, String.class);
        MessageProducer<String> outboxProducer = outboxFactory.createProducer(topic, String.class);
        
        CountDownLatch outboxLatch = new CountDownLatch(6);
        AtomicInteger outboxProcessed = new AtomicInteger();
        
        outboxGroup.addConsumer("outbox-member-1", message -> {
            outboxProcessed.incrementAndGet();
            outboxLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        outboxGroup.addConsumer("outbox-member-2", message -> {
            outboxProcessed.incrementAndGet();
            outboxLatch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        outboxGroup.start();
        
        // Send messages to outbox
        for (int i = 0; i < 6; i++) {
            outboxProducer.send("Outbox group message " + i).get(2, TimeUnit.SECONDS);
        }
        
        assertTrue(outboxLatch.await(15, TimeUnit.SECONDS));
        assertEquals(6, outboxProcessed.get());
        
        // Verify stats for both
        ConsumerGroupStats nativeStats = nativeGroup.getStats();
        ConsumerGroupStats outboxStats = outboxGroup.getStats();
        
        assertNotNull(nativeStats);
        assertNotNull(outboxStats);
        assertEquals(2, nativeStats.getActiveConsumerCount());
        assertEquals(2, outboxStats.getActiveConsumerCount());
        
        // Cleanup
        nativeGroup.stop();
        outboxGroup.stop();
        nativeProducer.close();
        outboxProducer.close();
        
        logger.info("✅ Consumer group comparison test passed");
    }
    
    @Test
    void testHealthAndResourceManagement() {
        // Test that both implementations properly manage health and resources
        assertTrue(nativeFactory.isHealthy());
        assertTrue(outboxFactory.isHealthy());
        
        // Create resources
        MessageProducer<String> nativeProducer = nativeFactory.createProducer("health-test", String.class);
        MessageConsumer<String> nativeConsumer = nativeFactory.createConsumer("health-test", String.class);
        MessageProducer<String> outboxProducer = outboxFactory.createProducer("health-test", String.class);
        MessageConsumer<String> outboxConsumer = outboxFactory.createConsumer("health-test", String.class);
        
        // Both should still be healthy
        assertTrue(nativeFactory.isHealthy());
        assertTrue(outboxFactory.isHealthy());
        
        // Close resources
        nativeProducer.close();
        nativeConsumer.close();
        outboxProducer.close();
        outboxConsumer.close();
        
        // Should still be healthy after cleanup
        assertTrue(nativeFactory.isHealthy());
        assertTrue(outboxFactory.isHealthy());
        
        logger.info("✅ Health and resource management test passed");
    }
}
