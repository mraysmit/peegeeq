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

import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.api.database.DatabaseService;
import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Simple test to verify consumer groups are working correctly.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-14
 * @version 1.0
 */
public class SimpleConsumerGroupTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleConsumerGroupTest.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("=== Simple Consumer Group Test ===");
        
        // Initialize PeeGeeQ Manager
        try (PeeGeeQManager manager = new PeeGeeQManager(
                new PeeGeeQConfiguration("development"), 
                new SimpleMeterRegistry())) {
            
            manager.start();
            logger.info("PeeGeeQ Manager started successfully");
            
            // Create database service and factory provider
            DatabaseService databaseService = new PgDatabaseService(manager);
            QueueFactoryProvider provider = new PgQueueFactoryProvider();
            
            // Create native queue factory
            QueueFactory nativeFactory = provider.createFactory("native", databaseService);
            
            // Test basic consumer group functionality
            testBasicConsumerGroup(nativeFactory);
            
            logger.info("Simple Consumer Group Test completed successfully!");
        }
    }
    
    private static void testBasicConsumerGroup(QueueFactory factory) throws Exception {
        logger.info("Testing basic consumer group functionality...");
        
        // Create a consumer group
        ConsumerGroup<String> consumerGroup = factory.createConsumerGroup(
            "TestGroup", "test-topic", String.class);
        
        // Create a producer
        MessageProducer<String> producer = factory.createProducer("test-topic", String.class);
        
        // Counter for processed messages
        CountDownLatch messageCounter = new CountDownLatch(6); // Expecting 6 messages to be processed
        
        // Add consumers with different filters
        consumerGroup.addConsumer("consumer-1", 
            createTestHandler("Consumer-1", messageCounter),
            MessageFilter.byHeader("region", "US"));
        
        consumerGroup.addConsumer("consumer-2", 
            createTestHandler("Consumer-2", messageCounter),
            MessageFilter.byHeader("region", "EU"));
        
        consumerGroup.addConsumer("consumer-3", 
            createTestHandler("Consumer-3", messageCounter),
            MessageFilter.acceptAll()); // This consumer accepts all messages
        
        // Start the consumer group
        consumerGroup.start();
        
        logger.info("Consumer group started with {} active consumers", 
            consumerGroup.getActiveConsumerCount());
        
        // Send test messages
        sendTestMessages(producer);
        
        // Wait for messages to be processed
        boolean allProcessed = messageCounter.await(30, TimeUnit.SECONDS);
        
        if (allProcessed) {
            logger.info("All messages processed successfully!");
        } else {
            logger.warn("Not all messages were processed within timeout");
        }
        
        // Print statistics
        ConsumerGroupStats stats = consumerGroup.getStats();
        logger.info("Consumer Group Statistics: {}", stats);
        
        // Print individual consumer statistics
        stats.getMemberStats().forEach((consumerId, memberStats) -> {
            logger.info("Consumer {}: {}", consumerId, memberStats);
        });
        
        // Cleanup
        consumerGroup.close();
        producer.close();
        
        logger.info("Basic consumer group test completed");
    }
    
    private static MessageHandler<String> createTestHandler(String consumerName, CountDownLatch counter) {
        return message -> {
            logger.info("[{}] Processing message: {} with headers: {}", 
                consumerName, message.getPayload(), message.getHeaders());
            
            // Simulate some processing time
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            counter.countDown();
            return CompletableFuture.completedFuture(null);
        };
    }
    
    private static void sendTestMessages(MessageProducer<String> producer) throws Exception {
        logger.info("Sending test messages...");
        
        // Send messages with different headers
        producer.send("Message for US", Map.of("region", "US"), "corr-1", "group-1")
            .whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Failed to send US message: {}", error.getMessage());
                } else {
                    logger.debug("Sent US message successfully");
                }
            });
        
        producer.send("Message for EU", Map.of("region", "EU"), "corr-2", "group-2")
            .whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Failed to send EU message: {}", error.getMessage());
                } else {
                    logger.debug("Sent EU message successfully");
                }
            });
        
        producer.send("Message for ASIA", Map.of("region", "ASIA"), "corr-3", "group-3")
            .whenComplete((result, error) -> {
                if (error != null) {
                    logger.error("Failed to send ASIA message: {}", error.getMessage());
                } else {
                    logger.debug("Sent ASIA message successfully");
                }
            });
        
        // Wait a bit for messages to be sent
        Thread.sleep(1000);
        
        logger.info("Test messages sent");
    }
}
