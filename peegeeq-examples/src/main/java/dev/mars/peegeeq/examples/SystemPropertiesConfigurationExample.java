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

import dev.mars.peegeeq.api.messaging.Message;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive example demonstrating all system properties that control runtime behavior.
 * 
 * This example shows how to configure and use the four key system properties:
 * - peegeeq.queue.max-retries: Controls retry attempts before dead letter queue
 * - peegeeq.queue.polling-interval: Controls message polling frequency  
 * - peegeeq.consumer.threads: Controls concurrent processing threads
 * - peegeeq.queue.batch-size: Controls batch processing size
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class SystemPropertiesConfigurationExample {
    
    private static final Logger logger = LoggerFactory.getLogger(SystemPropertiesConfigurationExample.class);
    
    public static void main(String[] args) {
        logger.info("🚀 Starting System Properties Configuration Example");
        
        try {
            // Run different configuration scenarios
            runHighThroughputConfiguration();
            Thread.sleep(2000);
            
            runLowLatencyConfiguration();
            Thread.sleep(2000);
            
            runReliableConfiguration();
            Thread.sleep(2000);
            
            runCustomConfiguration();
            
        } catch (Exception e) {
            logger.error("Example failed", e);
        }
        
        logger.info("✅ System Properties Configuration Example completed");
    }
    
    /**
     * Demonstrates high-throughput configuration for bulk processing.
     */
    private static void runHighThroughputConfiguration() throws Exception {
        logger.info("\n=== High Throughput Configuration ===");
        
        // Configure for high throughput
        System.setProperty("peegeeq.queue.max-retries", "5");
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");  // 1 second polling
        System.setProperty("peegeeq.consumer.threads", "8");           // 8 concurrent threads
        System.setProperty("peegeeq.queue.batch-size", "100");         // Large batches
        
        runScenario("high-throughput", "Optimized for maximum throughput with large batches");
    }
    
    /**
     * Demonstrates low-latency configuration for real-time processing.
     */
    private static void runLowLatencyConfiguration() throws Exception {
        logger.info("\n=== Low Latency Configuration ===");
        
        // Configure for low latency
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S"); // 100ms polling
        System.setProperty("peegeeq.consumer.threads", "2");            // Moderate concurrency
        System.setProperty("peegeeq.queue.batch-size", "1");            // Single message processing
        
        runScenario("low-latency", "Optimized for minimal latency with frequent polling");
    }
    
    /**
     * Demonstrates reliable configuration with extensive retry logic.
     */
    private static void runReliableConfiguration() throws Exception {
        logger.info("\n=== Reliable Configuration ===");
        
        // Configure for reliability
        System.setProperty("peegeeq.queue.max-retries", "10");
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");   // 2 second polling
        System.setProperty("peegeeq.consumer.threads", "4");            // Balanced concurrency
        System.setProperty("peegeeq.queue.batch-size", "25");           // Medium batches
        
        runScenario("reliable", "Optimized for reliability with extensive retry logic");
    }
    
    /**
     * Demonstrates custom configuration with specific business requirements.
     */
    private static void runCustomConfiguration() throws Exception {
        logger.info("\n=== Custom Business Configuration ===");
        
        // Configure for specific business needs
        System.setProperty("peegeeq.queue.max-retries", "7");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S"); // 500ms polling
        System.setProperty("peegeeq.consumer.threads", "6");            // Custom thread count
        System.setProperty("peegeeq.queue.batch-size", "50");           // Custom batch size
        
        runScenario("custom", "Custom configuration for specific business requirements");
    }
    
    /**
     * Runs a complete scenario with the current system properties configuration.
     */
    private static void runScenario(String scenarioName, String description) throws Exception {
        logger.info("📋 Scenario: {} - {}", scenarioName, description);
        
        // Initialize PeeGeeQ with current system properties
        try (PeeGeeQManager manager = new PeeGeeQManager("demo")) {
            manager.start();
            
            // Log the current configuration
            logCurrentConfiguration(manager);
            
            // Create queue factory
            QueueFactory queueFactory = manager.getQueueFactoryProvider()
                .createFactory("outbox", manager.getDatabaseService());
            
            String topic = "system-properties-demo-" + scenarioName;
            
            // Create producer and consumer
            MessageProducer<TestMessage> producer = queueFactory.createProducer(topic, TestMessage.class);
            MessageConsumer<TestMessage> consumer = queueFactory.createConsumer(topic, TestMessage.class);
            
            // Set up message processing
            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(5); // Wait for 5 messages
            
            consumer.subscribe(message -> {
                int count = processedCount.incrementAndGet();
                logger.info("📨 [{}] Processed message {} in thread: {} - Content: {}", 
                    scenarioName, count, Thread.currentThread().getName(), message.getPayload().content);
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
            
            // Send test messages
            logger.info("📤 Sending 5 test messages for scenario: {}", scenarioName);
            for (int i = 1; i <= 5; i++) {
                TestMessage message = new TestMessage(
                    "Message " + i + " for " + scenarioName,
                    Instant.now(),
                    "scenario-" + scenarioName
                );
                
                Map<String, String> headers = new HashMap<>();
                headers.put("scenario", scenarioName);
                headers.put("messageNumber", String.valueOf(i));
                
                producer.send(message, headers).join();
                logger.debug("📤 Sent message {}", i);
            }
            
            // Wait for processing with timeout
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            if (completed) {
                logger.info("✅ Scenario '{}' completed successfully - {} messages processed", 
                    scenarioName, processedCount.get());
            } else {
                logger.warn("⚠️ Scenario '{}' timed out - {} messages processed", 
                    scenarioName, processedCount.get());
            }
            
            // Cleanup
            consumer.close();
            producer.close();
            queueFactory.close();
        }
    }
    
    /**
     * Logs the current configuration values to show how system properties affect runtime behavior.
     */
    private static void logCurrentConfiguration(PeeGeeQManager manager) {
        var config = manager.getConfiguration().getQueueConfig();
        
        logger.info("🔧 Current Configuration:");
        logger.info("  📊 Max Retries: {} (peegeeq.queue.max-retries)", config.getMaxRetries());
        logger.info("  ⏱️ Polling Interval: {} (peegeeq.queue.polling-interval)", config.getPollingInterval());
        logger.info("  🧵 Consumer Threads: {} (peegeeq.consumer.threads)", config.getConsumerThreads());
        logger.info("  📦 Batch Size: {} (peegeeq.queue.batch-size)", config.getBatchSize());
    }
    
    /**
     * Test message class for demonstration.
     */
    public static class TestMessage {
        public String content;
        public Instant timestamp;
        public String category;
        
        public TestMessage() {}
        
        public TestMessage(String content, Instant timestamp, String category) {
            this.content = content;
            this.timestamp = timestamp;
            this.category = category;
        }
    }
}
