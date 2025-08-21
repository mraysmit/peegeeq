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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating retry and failure handling with configurable max retries.
 * 
 * This example shows how the peegeeq.queue.max-retries property controls:
 * - Number of retry attempts before moving to dead letter queue
 * - Failure handling behavior
 * - Dead letter queue processing
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-08-21
 * @version 1.0
 */
public class RetryAndFailureHandlingExample {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryAndFailureHandlingExample.class);
    
    public static void main(String[] args) {
        logger.info("🚀 Starting Retry and Failure Handling Example");
        
        try {
            // Demonstrate different retry configurations
            demonstrateQuickFailure();
            Thread.sleep(3000);
            
            demonstrateExtensiveRetries();
            Thread.sleep(3000);
            
            demonstrateSuccessfulRetry();
            
        } catch (Exception e) {
            logger.error("Example failed", e);
        }
        
        logger.info("✅ Retry and Failure Handling Example completed");
    }
    
    /**
     * Demonstrates quick failure with minimal retries.
     */
    private static void demonstrateQuickFailure() throws Exception {
        logger.info("\n=== Quick Failure Configuration (2 retries) ===");
        
        // Configure for quick failure
        System.setProperty("peegeeq.queue.max-retries", "2");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");
        System.setProperty("peegeeq.consumer.threads", "2");
        System.setProperty("peegeeq.queue.batch-size", "1");
        
        runFailureScenario("quick-failure", new AlwaysFailingProcessor(), 2);
    }
    
    /**
     * Demonstrates extensive retries before giving up.
     */
    private static void demonstrateExtensiveRetries() throws Exception {
        logger.info("\n=== Extensive Retries Configuration (8 retries) ===");
        
        // Configure for extensive retries
        System.setProperty("peegeeq.queue.max-retries", "8");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.3S");
        System.setProperty("peegeeq.consumer.threads", "1");
        System.setProperty("peegeeq.queue.batch-size", "1");
        
        runFailureScenario("extensive-retries", new AlwaysFailingProcessor(), 8);
    }
    
    /**
     * Demonstrates successful processing after some failures.
     */
    private static void demonstrateSuccessfulRetry() throws Exception {
        logger.info("\n=== Successful Retry Configuration (5 retries) ===");
        
        // Configure for moderate retries
        System.setProperty("peegeeq.queue.max-retries", "5");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.5S");
        System.setProperty("peegeeq.consumer.threads", "2");
        System.setProperty("peegeeq.queue.batch-size", "1");
        
        runFailureScenario("successful-retry", new EventuallySuccessfulProcessor(3), 5);
    }
    
    /**
     * Runs a failure scenario to demonstrate retry behavior.
     */
    private static void runFailureScenario(String scenarioName, MessageProcessor processor, int expectedMaxRetries) throws Exception {
        logger.info("📋 Scenario: {} - Max retries: {}", scenarioName, expectedMaxRetries);
        
        try (PeeGeeQManager manager = new PeeGeeQManager("demo")) {
            manager.start();
            
            // Log current configuration
            var config = manager.getConfiguration().getQueueConfig();
            logger.info("🔧 Configuration: maxRetries={}, pollingInterval={}, threads={}, batchSize={}", 
                config.getMaxRetries(), config.getPollingInterval(), 
                config.getConsumerThreads(), config.getBatchSize());
            
            QueueFactory queueFactory = manager.getQueueFactoryProvider()
                .createFactory("outbox", manager.getDatabaseService());
            
            String topic = "retry-demo-" + scenarioName;
            
            MessageProducer<FailureTestMessage> producer = queueFactory.createProducer(topic, FailureTestMessage.class);
            MessageConsumer<FailureTestMessage> consumer = queueFactory.createConsumer(topic, FailureTestMessage.class);
            
            CountDownLatch latch = new CountDownLatch(1);
            
            // Set up consumer with failure-prone processor
            consumer.subscribe(message -> {
                try {
                    return processor.process(message);
                } catch (Exception e) {
                    logger.error("❌ Processing failed for message {}: {}", 
                        message.getPayload().id, e.getMessage());
                    latch.countDown();
                    return CompletableFuture.failedFuture(e);
                } finally {
                    // Count down on success too (for eventually successful processor)
                    if (processor instanceof EventuallySuccessfulProcessor && 
                        ((EventuallySuccessfulProcessor) processor).hasSucceeded()) {
                        latch.countDown();
                    }
                }
            });
            
            // Send a test message that will fail
            FailureTestMessage message = new FailureTestMessage(
                "failure-test-" + System.currentTimeMillis(),
                "This message will fail initially",
                scenarioName
            );
            
            Map<String, String> headers = new HashMap<>();
            headers.put("scenario", scenarioName);
            headers.put("expectedRetries", String.valueOf(expectedMaxRetries));
            
            logger.info("📤 Sending message that will fail: {}", message.id);
            producer.send(message, headers).join();
            
            // Wait for processing to complete (success or final failure)
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (completed) {
                if (processor instanceof EventuallySuccessfulProcessor && 
                    ((EventuallySuccessfulProcessor) processor).hasSucceeded()) {
                    logger.info("✅ Message eventually processed successfully after {} attempts", 
                        ((EventuallySuccessfulProcessor) processor).getAttemptCount());
                } else {
                    logger.info("💀 Message failed after maximum retries and moved to dead letter queue");
                }
            } else {
                logger.warn("⚠️ Scenario timed out");
            }
            
            // Cleanup
            consumer.close();
            producer.close();
            queueFactory.close();
        }
    }
    
    /**
     * Interface for different message processing strategies.
     */
    interface MessageProcessor {
        CompletableFuture<Void> process(Message<FailureTestMessage> message) throws Exception;
    }
    
    /**
     * Processor that always fails to demonstrate retry behavior.
     */
    static class AlwaysFailingProcessor implements MessageProcessor {
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        
        @Override
        public CompletableFuture<Void> process(Message<FailureTestMessage> message) throws Exception {
            int attempt = attemptCount.incrementAndGet();
            logger.warn("💥 Attempt {} - Simulated failure for message: {}", attempt, message.getPayload().id);
            throw new RuntimeException("Simulated processing failure (attempt " + attempt + ")");
        }
    }
    
    /**
     * Processor that fails a few times then succeeds.
     */
    static class EventuallySuccessfulProcessor implements MessageProcessor {
        private final AtomicInteger attemptCount = new AtomicInteger(0);
        private final int failuresBeforeSuccess;
        private boolean succeeded = false;
        
        public EventuallySuccessfulProcessor(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }
        
        @Override
        public CompletableFuture<Void> process(Message<FailureTestMessage> message) throws Exception {
            int attempt = attemptCount.incrementAndGet();
            
            if (attempt <= failuresBeforeSuccess) {
                logger.warn("💥 Attempt {} - Simulated failure for message: {} (will succeed on attempt {})", 
                    attempt, message.getPayload().id, failuresBeforeSuccess + 1);
                throw new RuntimeException("Simulated processing failure (attempt " + attempt + ")");
            } else {
                logger.info("✅ Attempt {} - Successfully processed message: {}", attempt, message.getPayload().id);
                succeeded = true;
                return CompletableFuture.completedFuture(null);
            }
        }
        
        public boolean hasSucceeded() { return succeeded; }
        public int getAttemptCount() { return attemptCount.get(); }
    }
    
    /**
     * Test message class for failure scenarios.
     */
    public static class FailureTestMessage {
        public String id;
        public String content;
        public String scenario;
        public Instant timestamp;
        
        public FailureTestMessage() {}
        
        public FailureTestMessage(String id, String content, String scenario) {
            this.id = id;
            this.content = content;
            this.scenario = scenario;
            this.timestamp = Instant.now();
        }
    }
}
