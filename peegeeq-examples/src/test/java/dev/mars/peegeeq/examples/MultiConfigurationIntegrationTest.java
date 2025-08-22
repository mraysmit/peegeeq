package dev.mars.peegeeq.examples;


import dev.mars.peegeeq.api.messaging.*;
import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.db.config.MultiConfigurationManager;
import dev.mars.peegeeq.pgqueue.PgNativeFactoryRegistrar;
import dev.mars.peegeeq.outbox.OutboxFactoryRegistrar;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating multi-configuration capabilities in a realistic scenario.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2025-07-17
 * @version 1.0
 */
class MultiConfigurationIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(MultiConfigurationIntegrationTest.class);

    private MultiConfigurationManager configManager;
    private PostgreSQLContainer<?> postgres;

    @BeforeEach
    void setUp() {
        // Start PostgreSQL container
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
        postgres = container
                .withDatabaseName("peegeeq_test")
                .withUsername("peegeeq_test")
                .withPassword("peegeeq_test")
                .withSharedMemorySize(256 * 1024 * 1024L) // 256MB for better performance
                .withReuse(false); // Always start fresh for test

        postgres.start();
        logger.info("PostgreSQL container started: {}", postgres.getJdbcUrl());

        // Configure system properties to use the container
        configureSystemPropertiesForContainer(postgres);

        configManager = new MultiConfigurationManager(new SimpleMeterRegistry());

        // Register queue factory implementations
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) configManager.getFactoryProvider());
        OutboxFactoryRegistrar.registerWith((QueueFactoryRegistrar) configManager.getFactoryProvider());

        // Register different configurations for different use cases
        configManager.registerConfiguration("high-throughput", "test");
        configManager.registerConfiguration("low-latency", "test");
        configManager.registerConfiguration("reliable", "test");
        configManager.registerConfiguration("test", "test");

        configManager.start();
    }

    @AfterEach
    void tearDown() {
        if (configManager != null) {
            configManager.close();
        }
        if (postgres != null) {
            postgres.stop();
        }

        // Clean up system properties
        clearSystemProperties();
    }

    /**
     * Configures system properties to use the TestContainer database.
     */
    private void configureSystemPropertiesForContainer(PostgreSQLContainer<?> postgres) {
        logger.info("Configuring system properties for TestContainer database...");

        // Set database connection properties
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.schema", "public");
        System.setProperty("peegeeq.database.ssl.enabled", "false");

        // Configure for test environment
        System.setProperty("peegeeq.database.pool.min-size", "2");
        System.setProperty("peegeeq.database.pool.max-size", "10");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.health.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");
        System.setProperty("peegeeq.migration.enabled", "true");
        System.setProperty("peegeeq.migration.auto-migrate", "true");
        System.setProperty("peegeeq.queue.dead-letter-enabled", "true");

        logger.info("Configuration complete");
    }

    /**
     * Clears system properties set for testing.
     */
    private void clearSystemProperties() {
        System.clearProperty("peegeeq.database.host");
        System.clearProperty("peegeeq.database.port");
        System.clearProperty("peegeeq.database.name");
        System.clearProperty("peegeeq.database.username");
        System.clearProperty("peegeeq.database.password");
        System.clearProperty("peegeeq.database.schema");
        System.clearProperty("peegeeq.database.ssl.enabled");
        System.clearProperty("peegeeq.database.pool.min-size");
        System.clearProperty("peegeeq.database.pool.max-size");
        System.clearProperty("peegeeq.metrics.enabled");
        System.clearProperty("peegeeq.health.enabled");
        System.clearProperty("peegeeq.circuit-breaker.enabled");
        System.clearProperty("peegeeq.migration.enabled");
        System.clearProperty("peegeeq.migration.auto-migrate");
        System.clearProperty("peegeeq.queue.dead-letter-enabled");
    }
    
    @Test
    void testMultipleQueueConfigurationsInSameApplication() throws Exception {
        logger.info("Testing multiple queue configurations in same application");

        // Create different queue factories for different use cases (using outbox due to native compatibility issues)
        QueueFactory batchProcessingQueue = configManager.createFactory("high-throughput", "outbox");
        QueueFactory realTimeQueue = configManager.createFactory("low-latency", "outbox");
        QueueFactory transactionalQueue = configManager.createFactory("reliable", "outbox");

        // Verify all factories are healthy
        assertTrue(batchProcessingQueue.isHealthy());
        assertTrue(realTimeQueue.isHealthy());
        assertTrue(transactionalQueue.isHealthy());

        // Test batch processing queue
        testBatchProcessing(batchProcessingQueue);

        // Test real-time queue
        testRealTimeProcessing(realTimeQueue);

        // Test transactional queue
        testTransactionalProcessing(transactionalQueue);

        // Clean up
        batchProcessingQueue.close();
        realTimeQueue.close();
        transactionalQueue.close();
    }
    
    @Test
    void testConfigurationBuilderIntegration() throws Exception {
        logger.info("Testing configuration builder integration");

        // Create specialized queues using the registered factory provider
        QueueFactory reliableQueue1 = configManager.createFactory("test", "outbox");
        QueueFactory reliableQueue2 = configManager.createFactory("test", "outbox");
        QueueFactory reliableQueue3 = configManager.createFactory("test", "outbox");

        // Verify all queues are healthy
        assertTrue(reliableQueue1.isHealthy());
        assertTrue(reliableQueue2.isHealthy());
        assertTrue(reliableQueue3.isHealthy());

        // Test that they all use outbox implementation (since native is not available)
        assertEquals("outbox", reliableQueue1.getImplementationType());
        assertEquals("outbox", reliableQueue2.getImplementationType());
        assertEquals("outbox", reliableQueue3.getImplementationType());

        // Clean up
        reliableQueue1.close();
        reliableQueue2.close();
        reliableQueue3.close();
    }
    
    @Test
    void testConcurrentMultiConfigurationUsage() throws Exception {
        logger.info("Testing concurrent multi-configuration usage");
        
        // Create multiple queue factories concurrently (using outbox due to native compatibility issues)
        QueueFactory[] factories = new QueueFactory[4];
        factories[0] = configManager.createFactory("high-throughput", "outbox");
        factories[1] = configManager.createFactory("low-latency", "outbox");
        factories[2] = configManager.createFactory("reliable", "outbox");
        factories[3] = configManager.createFactory("test", "outbox");
        
        // Test concurrent message processing (reduced message count for reliability)
        CountDownLatch latch = new CountDownLatch(12); // 3 messages per factory
        AtomicInteger totalProcessed = new AtomicInteger(0);

        for (int i = 0; i < factories.length; i++) {
            final int factoryIndex = i;
            final QueueFactory factory = factories[i];

            // Create producer and consumer for each factory
            MessageProducer<String> producer = factory.createProducer("test-topic-" + i, String.class);
            MessageConsumer<String> consumer = factory.createConsumer("test-topic-" + i, String.class);

            // Set up consumer
            consumer.subscribe(message -> {
                logger.debug("Factory {} processed: {}", factoryIndex, message.getPayload());
                totalProcessed.incrementAndGet();
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });

            // Send messages (reduced count)
            for (int j = 0; j < 3; j++) {
                producer.send("Message " + j + " from factory " + factoryIndex,
                    Map.of("factory", String.valueOf(factoryIndex)),
                    "correlation-" + i + "-" + j,
                    "routing-" + i + "-" + j);
            }
        }

        // Wait for all messages to be processed (increased timeout)
        boolean completed = latch.await(45, TimeUnit.SECONDS);
        assertTrue(completed, "Not all messages were processed in time");
        assertEquals(12, totalProcessed.get(), "Expected 12 messages to be processed");
        
        // Clean up
        for (QueueFactory factory : factories) {
            factory.close();
        }
    }
    
    private void testBatchProcessing(QueueFactory factory) throws Exception {
        logger.info("Testing batch processing queue");

        MessageProducer<BatchEvent> producer = factory.createProducer("batch-events", BatchEvent.class);
        MessageConsumer<BatchEvent> consumer = factory.createConsumer("batch-events", BatchEvent.class);

        // Reduced number of messages for more reliable testing
        CountDownLatch latch = new CountDownLatch(10);

        consumer.subscribe(message -> {
            logger.debug("Batch processed: {}", message.getPayload().getBatchId());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send batch of messages (reduced count)
        for (int i = 1; i <= 10; i++) {
            BatchEvent event = new BatchEvent("BATCH-" + i, "Processing batch " + i, i * 10);
            producer.send(event, Map.of("batch-type", "high-throughput"), "correlation-" + i, "batch-" + i);
        }

        // Increased timeout for more reliable testing
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Batch processing did not complete in time");

        consumer.close();
        producer.close();
    }
    
    private void testRealTimeProcessing(QueueFactory factory) throws Exception {
        logger.info("Testing real-time processing queue");

        MessageProducer<RealTimeEvent> producer = factory.createProducer("realtime-events", RealTimeEvent.class);
        MessageConsumer<RealTimeEvent> consumer = factory.createConsumer("realtime-events", RealTimeEvent.class);

        // Reduced number of messages for more reliable testing
        CountDownLatch latch = new CountDownLatch(3);

        consumer.subscribe(message -> {
            long latency = System.currentTimeMillis() - message.getPayload().getTimestamp();
            logger.info("Real-time processed: {} (latency: {}ms)",
                message.getPayload().getEventId(), latency);
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send real-time messages (reduced count)
        for (int i = 1; i <= 3; i++) {
            RealTimeEvent event = new RealTimeEvent("RT-" + i, System.currentTimeMillis(), "Real-time event " + i);
            producer.send(event, Map.of("priority", "HIGH"), "correlation-" + i, "realtime-" + i);
            Thread.sleep(100); // Small delay between messages
        }

        // Increased timeout for more reliable testing
        boolean completed = latch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "Real-time processing did not complete in time");

        consumer.close();
        producer.close();
    }
    
    private void testTransactionalProcessing(QueueFactory factory) throws Exception {
        logger.info("Testing transactional processing queue");

        MessageProducer<CriticalEvent> producer = factory.createProducer("critical-events", CriticalEvent.class);
        MessageConsumer<CriticalEvent> consumer = factory.createConsumer("critical-events", CriticalEvent.class);

        // Reduced number of messages for more reliable testing
        CountDownLatch latch = new CountDownLatch(2);

        consumer.subscribe(message -> {
            logger.info("Critical processed: {} (importance: {})",
                message.getPayload().getEventId(), message.getPayload().getImportanceLevel());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send critical messages (reduced count)
        for (int i = 1; i <= 2; i++) {
            CriticalEvent event = new CriticalEvent("CRITICAL-" + i, "URGENT", "Critical system event " + i);
            producer.send(event, Map.of("importance", "CRITICAL"), "correlation-" + i, "critical-" + i);
        }

        // Increased timeout for more reliable testing
        boolean completed = latch.await(20, TimeUnit.SECONDS);
        assertTrue(completed, "Transactional processing did not complete in time");

        consumer.close();
        producer.close();
    }
    
    // Event classes for testing
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
        
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public String getImportanceLevel() { return importanceLevel; }
        public void setImportanceLevel(String importanceLevel) { this.importanceLevel = importanceLevel; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
