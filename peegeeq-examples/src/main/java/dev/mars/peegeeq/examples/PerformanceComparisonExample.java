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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance comparison example showing the impact of different system property configurations.
 * 
 * This example demonstrates how different combinations of:
 * - peegeeq.consumer.threads (concurrency)
 * - peegeeq.queue.batch-size (batching)
 * - peegeeq.queue.polling-interval (polling frequency)
 * 
 * affect overall system performance and throughput.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class PerformanceComparisonExample {
    
    private static final Logger logger = LoggerFactory.getLogger(PerformanceComparisonExample.class);
    private static final int MESSAGE_COUNT = 50; // Number of messages to process in each test
    
    public static void main(String[] args) {
        logger.info("🚀 Starting Performance Comparison Example");
        
        try {
            // Test different configurations and compare performance
            PerformanceResult singleThreaded = testConfiguration("Single-Threaded", 1, 1, "PT1S");
            Thread.sleep(2000);
            
            PerformanceResult multiThreaded = testConfiguration("Multi-Threaded", 4, 1, "PT1S");
            Thread.sleep(2000);
            
            PerformanceResult batched = testConfiguration("Batched Processing", 2, 25, "PT1S");
            Thread.sleep(2000);
            
            PerformanceResult fastPolling = testConfiguration("Fast Polling", 2, 10, "PT0.1S");
            Thread.sleep(2000);
            
            PerformanceResult optimized = testConfiguration("Optimized", 6, 50, "PT0.2S");
            
            // Display comparison results
            displayPerformanceComparison(singleThreaded, multiThreaded, batched, fastPolling, optimized);
            
        } catch (Exception e) {
            logger.error("Performance comparison failed", e);
        }
        
        logger.info("✅ Performance Comparison Example completed");
    }
    
    /**
     * Tests a specific configuration and measures performance.
     */
    private static PerformanceResult testConfiguration(String configName, int threads, int batchSize, String pollingInterval) throws Exception {
        logger.info("\n=== Testing Configuration: {} ===", configName);
        logger.info("🔧 Threads: {}, Batch Size: {}, Polling Interval: {}", threads, batchSize, pollingInterval);
        
        // Set system properties
        System.setProperty("peegeeq.queue.max-retries", "3");
        System.setProperty("peegeeq.consumer.threads", String.valueOf(threads));
        System.setProperty("peegeeq.queue.batch-size", String.valueOf(batchSize));
        System.setProperty("peegeeq.queue.polling-interval", pollingInterval);
        
        Instant startTime = Instant.now();
        
        try (PeeGeeQManager manager = new PeeGeeQManager("demo")) {
            manager.start();
            
            QueueFactory queueFactory = manager.getQueueFactoryProvider()
                .createFactory("outbox", manager.getDatabaseService());
            
            String topic = "perf-test-" + configName.toLowerCase().replace(" ", "-");
            
            MessageProducer<PerformanceTestMessage> producer = queueFactory.createProducer(topic, PerformanceTestMessage.class);
            MessageConsumer<PerformanceTestMessage> consumer = queueFactory.createConsumer(topic, PerformanceTestMessage.class);
            
            // Performance tracking
            AtomicInteger processedCount = new AtomicInteger(0);
            AtomicLong totalProcessingTime = new AtomicLong(0);
            CountDownLatch latch = new CountDownLatch(MESSAGE_COUNT);
            
            // Set up consumer
            consumer.subscribe(message -> {
                Instant processingStart = Instant.now();
                
                // Simulate some processing work
                try {
                    Thread.sleep(10); // 10ms processing time per message
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                long processingTimeMs = Duration.between(processingStart, Instant.now()).toMillis();
                totalProcessingTime.addAndGet(processingTimeMs);
                
                int count = processedCount.incrementAndGet();
                if (count % 10 == 0) {
                    logger.debug("📊 [{}] Processed {} messages", configName, count);
                }
                
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
            
            // Send messages
            Instant sendingStart = Instant.now();
            logger.info("📤 Sending {} messages...", MESSAGE_COUNT);
            
            for (int i = 1; i <= MESSAGE_COUNT; i++) {
                PerformanceTestMessage message = new PerformanceTestMessage(
                    "perf-test-" + i,
                    "Performance test message " + i,
                    configName
                );
                
                Map<String, String> headers = new HashMap<>();
                headers.put("config", configName);
                headers.put("messageNumber", String.valueOf(i));
                headers.put("sendTime", Instant.now().toString());
                
                producer.send(message, headers).join();
            }
            
            Instant sendingEnd = Instant.now();
            long sendingTimeMs = Duration.between(sendingStart, sendingEnd).toMillis();
            
            // Wait for all messages to be processed
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            Instant endTime = Instant.now();
            
            // Calculate metrics
            long totalTimeMs = Duration.between(startTime, endTime).toMillis();
            long processingTimeMs = Duration.between(sendingEnd, endTime).toMillis();
            double throughput = completed ? (MESSAGE_COUNT * 1000.0) / totalTimeMs : 0;
            double avgProcessingTime = processedCount.get() > 0 ? totalProcessingTime.get() / (double) processedCount.get() : 0;
            
            PerformanceResult result = new PerformanceResult(
                configName, threads, batchSize, pollingInterval,
                completed, processedCount.get(), totalTimeMs, sendingTimeMs, 
                processingTimeMs, throughput, avgProcessingTime
            );
            
            logger.info("📊 Results for {}: {} messages in {}ms (throughput: {:.2f} msg/sec)", 
                configName, processedCount.get(), totalTimeMs, throughput);
            
            // Cleanup
            consumer.close();
            producer.close();
            queueFactory.close();
            
            return result;
        }
    }
    
    /**
     * Displays a comparison of all performance results.
     */
    private static void displayPerformanceComparison(PerformanceResult... results) {
        logger.info("\n" + "=".repeat(80));
        logger.info("📊 PERFORMANCE COMPARISON RESULTS");
        logger.info("=".repeat(80));
        
        logger.info(String.format("%-20s %-8s %-10s %-12s %-10s %-12s %-10s", 
            "Configuration", "Threads", "BatchSize", "PollingInt", "Messages", "TotalTime", "Throughput"));
        logger.info("-".repeat(80));
        
        for (PerformanceResult result : results) {
            logger.info(String.format("%-20s %-8d %-10d %-12s %-10d %-12dms %-10.2f", 
                result.configName, result.threads, result.batchSize, result.pollingInterval,
                result.processedMessages, result.totalTimeMs, result.throughputMsgPerSec));
        }
        
        logger.info("-".repeat(80));
        
        // Find best performing configuration
        PerformanceResult best = null;
        for (PerformanceResult result : results) {
            if (result.completed && (best == null || result.throughputMsgPerSec > best.throughputMsgPerSec)) {
                best = result;
            }
        }
        
        if (best != null) {
            logger.info("🏆 Best Performance: {} with {:.2f} messages/second", 
                best.configName, best.throughputMsgPerSec);
            logger.info("🔧 Optimal Settings: {} threads, batch size {}, polling interval {}", 
                best.threads, best.batchSize, best.pollingInterval);
        }
        
        logger.info("=".repeat(80));
    }
    
    /**
     * Performance test result data.
     */
    static class PerformanceResult {
        final String configName;
        final int threads;
        final int batchSize;
        final String pollingInterval;
        final boolean completed;
        final int processedMessages;
        final long totalTimeMs;
        final long sendingTimeMs;
        final long processingTimeMs;
        final double throughputMsgPerSec;
        final double avgProcessingTimeMs;
        
        PerformanceResult(String configName, int threads, int batchSize, String pollingInterval,
                         boolean completed, int processedMessages, long totalTimeMs, long sendingTimeMs,
                         long processingTimeMs, double throughputMsgPerSec, double avgProcessingTimeMs) {
            this.configName = configName;
            this.threads = threads;
            this.batchSize = batchSize;
            this.pollingInterval = pollingInterval;
            this.completed = completed;
            this.processedMessages = processedMessages;
            this.totalTimeMs = totalTimeMs;
            this.sendingTimeMs = sendingTimeMs;
            this.processingTimeMs = processingTimeMs;
            this.throughputMsgPerSec = throughputMsgPerSec;
            this.avgProcessingTimeMs = avgProcessingTimeMs;
        }
    }
    
    /**
     * Test message for performance testing.
     */
    public static class PerformanceTestMessage {
        public String id;
        public String content;
        public String configName;
        public Instant timestamp;
        
        public PerformanceTestMessage() {}
        
        public PerformanceTestMessage(String id, String content, String configName) {
            this.id = id;
            this.content = content;
            this.configName = configName;
            this.timestamp = Instant.now();
        }
    }
}
