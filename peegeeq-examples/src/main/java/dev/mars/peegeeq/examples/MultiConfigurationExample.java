package dev.mars.peegeeq.examples;

import dev.mars.peegeeq.api.*;
import dev.mars.peegeeq.db.config.MultiConfigurationManager;
import dev.mars.peegeeq.db.config.QueueConfigurationBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating multiple queue configurations for different use cases
 * within the same application.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
public class MultiConfigurationExample {
    
    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationExample.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("Starting Multi-Configuration Example");
        
        // Initialize multi-configuration manager
        try (MultiConfigurationManager configManager = new MultiConfigurationManager(new SimpleMeterRegistry())) {
            
            // Register different configurations for different use cases
            registerConfigurations(configManager);
            
            // Start all configurations
            configManager.start();
            
            // Demonstrate different queue types
            demonstrateHighThroughputQueue(configManager);
            demonstrateLowLatencyQueue(configManager);
            demonstrateReliableQueue(configManager);
            demonstrateCustomConfiguration(configManager);
            
            logger.info("Multi-Configuration Example completed successfully!");
        }
    }
    
    private static void registerConfigurations(MultiConfigurationManager configManager) {
        logger.info("Registering multiple configurations...");
        
        // Register high-throughput configuration for batch processing
        configManager.registerConfiguration("high-throughput", "high-throughput");
        
        // Register low-latency configuration for real-time processing
        configManager.registerConfiguration("low-latency", "low-latency");
        
        // Register reliable configuration for critical messages
        configManager.registerConfiguration("reliable", "reliable");
        
        // Register development configuration for testing
        configManager.registerConfiguration("development", "development");
        
        logger.info("Registered {} configurations: {}", 
            configManager.getConfigurationNames().size(),
            configManager.getConfigurationNames());
    }
    
    private static void demonstrateHighThroughputQueue(MultiConfigurationManager configManager) throws Exception {
        logger.info("=== Demonstrating High-Throughput Queue ===");
        
        // Create high-throughput queue factory
        QueueFactory factory = configManager.createFactory("high-throughput", "native");
        
        // Create producer and consumer
        MessageProducer<BatchEvent> producer = factory.createProducer("batch-events", BatchEvent.class);
        MessageConsumer<BatchEvent> consumer = factory.createConsumer("batch-events", BatchEvent.class);
        
        // Process messages in batches
        CountDownLatch latch = new CountDownLatch(100);
        
        consumer.subscribe(message -> {
            logger.debug("High-throughput processed: {}", message.getPayload().getBatchId());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send batch of messages
        for (int i = 1; i <= 100; i++) {
            BatchEvent event = new BatchEvent("BATCH-" + i, "Processing batch " + i, i * 10);
            producer.send(event, Map.of("batch-type", "high-throughput"), "correlation-" + i, "batch-" + i);
        }
        
        // Wait for processing
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        logger.info("High-throughput processing completed: {}", completed);
        
        consumer.close();
        producer.close();
        factory.close();
    }
    
    private static void demonstrateLowLatencyQueue(MultiConfigurationManager configManager) throws Exception {
        logger.info("=== Demonstrating Low-Latency Queue ===");
        
        // Create low-latency queue factory
        QueueFactory factory = configManager.createFactory("low-latency", "native");
        
        // Create producer and consumer
        MessageProducer<RealTimeEvent> producer = factory.createProducer("realtime-events", RealTimeEvent.class);
        MessageConsumer<RealTimeEvent> consumer = factory.createConsumer("realtime-events", RealTimeEvent.class);
        
        // Process messages with minimal latency
        CountDownLatch latch = new CountDownLatch(10);
        
        consumer.subscribe(message -> {
            long latency = System.currentTimeMillis() - message.getPayload().getTimestamp();
            logger.info("Low-latency processed: {} (latency: {}ms)", 
                message.getPayload().getEventId(), latency);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send real-time messages
        for (int i = 1; i <= 10; i++) {
            RealTimeEvent event = new RealTimeEvent("RT-" + i, System.currentTimeMillis(), "Real-time event " + i);
            producer.send(event, Map.of("priority", "HIGH"), "correlation-" + i, "realtime-" + i);
            Thread.sleep(100); // Small delay between messages
        }
        
        // Wait for processing
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        logger.info("Low-latency processing completed: {}", completed);
        
        consumer.close();
        producer.close();
        factory.close();
    }
    
    private static void demonstrateReliableQueue(MultiConfigurationManager configManager) throws Exception {
        logger.info("=== Demonstrating Reliable Queue ===");
        
        // Create reliable queue factory (using outbox pattern)
        QueueFactory factory = configManager.createFactory("reliable", "outbox");
        
        // Create producer and consumer
        MessageProducer<CriticalEvent> producer = factory.createProducer("critical-events", CriticalEvent.class);
        MessageConsumer<CriticalEvent> consumer = factory.createConsumer("critical-events", CriticalEvent.class);
        
        // Process critical messages with reliability guarantees
        CountDownLatch latch = new CountDownLatch(5);
        
        consumer.subscribe(message -> {
            logger.info("Reliable processed: {} (importance: {})", 
                message.getPayload().getEventId(), message.getPayload().getImportanceLevel());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send critical messages
        for (int i = 1; i <= 5; i++) {
            CriticalEvent event = new CriticalEvent("CRITICAL-" + i, "URGENT", "Critical system event " + i);
            producer.send(event, Map.of("importance", "CRITICAL"), "correlation-" + i, "critical-" + i);
        }
        
        // Wait for processing
        boolean completed = latch.await(15, TimeUnit.SECONDS);
        logger.info("Reliable processing completed: {}", completed);
        
        consumer.close();
        producer.close();
        factory.close();
    }
    
    private static void demonstrateCustomConfiguration(MultiConfigurationManager configManager) throws Exception {
        logger.info("=== Demonstrating Custom Configuration ===");
        
        // Create custom queue using builder pattern
        QueueFactory factory = QueueConfigurationBuilder.createCustomQueue(
            configManager.getDatabaseService("development"),
            "native",
            5,                              // batch size
            java.time.Duration.ofMillis(500), // polling interval
            3,                              // max retries
            java.time.Duration.ofSeconds(30), // visibility timeout
            true                            // dead letter enabled
        );
        
        // Create producer and consumer
        MessageProducer<String> producer = factory.createProducer("custom-events", String.class);
        MessageConsumer<String> consumer = factory.createConsumer("custom-events", String.class);
        
        // Process messages with custom settings
        CountDownLatch latch = new CountDownLatch(3);
        
        consumer.subscribe(message -> {
            logger.info("Custom processed: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        
        // Send custom messages
        for (int i = 1; i <= 3; i++) {
            producer.send("Custom message " + i, Map.of("type", "custom"), "correlation-" + i, "custom-" + i);
        }
        
        // Wait for processing
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        logger.info("Custom processing completed: {}", completed);
        
        consumer.close();
        producer.close();
        factory.close();
    }
    
    // Event classes for different use cases
    public static class BatchEvent {
        private String batchId;
        private String description;
        private int recordCount;
        
        public BatchEvent() {}
        
        public BatchEvent(String batchId, String description, int recordCount) {
            this.batchId = batchId;
            this.description = description;
            this.recordCount = recordCount;
        }
        
        // Getters and setters
        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getRecordCount() { return recordCount; }
        public void setRecordCount(int recordCount) { this.recordCount = recordCount; }
    }
    
    public static class RealTimeEvent {
        private String eventId;
        private long timestamp;
        private String data;
        
        public RealTimeEvent() {}
        
        public RealTimeEvent(String eventId, long timestamp, String data) {
            this.eventId = eventId;
            this.timestamp = timestamp;
            this.data = data;
        }
        
        // Getters and setters
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }
    
    public static class CriticalEvent {
        private String eventId;
        private String importanceLevel;
        private String message;
        
        public CriticalEvent() {}
        
        public CriticalEvent(String eventId, String importanceLevel, String message) {
            this.eventId = eventId;
            this.importanceLevel = importanceLevel;
            this.message = message;
        }
        
        // Getters and setters
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public String getImportanceLevel() { return importanceLevel; }
        public void setImportanceLevel(String importanceLevel) { this.importanceLevel = importanceLevel; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
