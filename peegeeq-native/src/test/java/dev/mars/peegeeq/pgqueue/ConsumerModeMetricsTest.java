package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Metrics integration tests for different consumer modes.
 * Tests that metrics are properly recorded for LISTEN_NOTIFY_ONLY, POLLING_ONLY, and HYBRID modes.
 */
@Testcontainers
public class ConsumerModeMetricsTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeMetricsTest.class);

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private PeeGeeQManager manager;
    private QueueFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        logger.info("🔧 Setting up ConsumerModeMetricsTest");
        
        // Clear any existing system properties
        System.clearProperty("peegeeq.queue.polling-interval");
        System.clearProperty("peegeeq.queue.visibility-timeout");
        System.clearProperty("peegeeq.queue.batch-size");
        System.clearProperty("peegeeq.consumer.threads");

        initializeManagerAndFactory();
        logger.info("✅ ConsumerModeMetricsTest setup completed");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("🧹 ConsumerModeMetricsTest teardown completed");
    }

    private void initializeManagerAndFactory() throws Exception {
        // Configure test properties using TestContainer pattern (following established patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT0.1S"); // Fast polling for metrics tests
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Initialize meter registry
        meterRegistry = new SimpleMeterRegistry();

        // Initialize PeeGeeQ with test configuration
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, meterRegistry);
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);
    }

    @Test
    void testMessageCountMetricsAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing message count metrics across consumer modes");

        String topicName = "test-message-count-metrics";
        int messageCount = 5;

        // Test each consumer mode
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};
        
        for (ConsumerMode mode : modes) {
            logger.info("📊 Testing message count metrics for mode: {}", mode);
            
            testMessageCountMetricsForMode(topicName + "-" + mode.name().toLowerCase(), mode, messageCount);
        }

        logger.info("✅ Message count metrics test completed successfully");
    }

    @Test
    void testProcessingTimeMetricsAcrossConsumerModes() throws Exception {
        logger.info("🧪 Testing processing time metrics across consumer modes");

        String topicName = "test-processing-time-metrics";
        int messageCount = 3;

        // Test each consumer mode
        ConsumerMode[] modes = {ConsumerMode.LISTEN_NOTIFY_ONLY, ConsumerMode.POLLING_ONLY, ConsumerMode.HYBRID};
        
        for (ConsumerMode mode : modes) {
            logger.info("⏱️ Testing processing time metrics for mode: {}", mode);
            
            testProcessingTimeMetricsForMode(topicName + "-" + mode.name().toLowerCase(), mode, messageCount);
        }

        logger.info("✅ Processing time metrics test completed successfully");
    }

    @Test
    void testQueueDepthMetrics() throws Exception {
        logger.info("🧪 Testing queue depth metrics");

        String topicName = "test-queue-depth-metrics";
        
        // Get initial queue depth
        Gauge queueDepthGauge = meterRegistry.find("peegeeq.queue.depth.native").gauge();
        assertNotNull(queueDepthGauge, "Queue depth gauge should be registered");
        
        double initialDepth = queueDepthGauge.value();
        logger.info("📊 Initial queue depth: {}", initialDepth);

        // Send messages to increase queue depth
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        for (int i = 0; i < 3; i++) {
            producer.send("Queue depth test message " + i);
        }
        
        // Wait a moment for metrics to update
        Thread.sleep(1000);
        
        // Check that queue depth increased
        double newDepth = queueDepthGauge.value();
        logger.info("📊 Queue depth after sending messages: {}", newDepth);
        assertTrue(newDepth >= initialDepth, "Queue depth should increase after sending messages");

        // Create consumer to process messages
        CountDownLatch latch = new CountDownLatch(3);
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for messages to be processed
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All messages should be processed");
        
        // Wait for metrics to update
        Thread.sleep(1000);
        
        // Check that queue depth decreased
        double finalDepth = queueDepthGauge.value();
        logger.info("📊 Final queue depth after processing: {}", finalDepth);
        assertTrue(finalDepth <= newDepth, "Queue depth should decrease after processing messages");

        consumer.close();
        producer.close();
        
        logger.info("✅ Queue depth metrics test completed successfully");
    }

    private void testMessageCountMetricsForMode(String topicName, ConsumerMode mode, int messageCount) throws Exception {
        // Get initial metric values
        Counter sentCounter = meterRegistry.find("peegeeq.messages.sent").counter();
        Counter receivedCounter = meterRegistry.find("peegeeq.messages.received").counter();
        Counter processedCounter = meterRegistry.find("peegeeq.messages.processed").counter();
        
        assertNotNull(sentCounter, "Messages sent counter should be registered");
        assertNotNull(receivedCounter, "Messages received counter should be registered");
        assertNotNull(processedCounter, "Messages processed counter should be registered");
        
        double initialSent = sentCounter.count();
        double initialReceived = receivedCounter.count();
        double initialProcessed = processedCounter.count();
        
        logger.info("📊 Initial metrics - Sent: {}, Received: {}, Processed: {}", 
            initialSent, initialReceived, initialProcessed);

        // Create consumer and producer
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            processedCount.incrementAndGet();
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(1000);

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Metrics test message " + i);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All messages should be processed within timeout");
        assertEquals(messageCount, processedCount.get(), "All messages should be processed");

        // Wait for metrics to be updated
        Thread.sleep(1000);

        // Verify metrics were updated
        double finalSent = sentCounter.count();
        double finalReceived = receivedCounter.count();
        double finalProcessed = processedCounter.count();
        
        logger.info("📊 Final metrics - Sent: {}, Received: {}, Processed: {}", 
            finalSent, finalReceived, finalProcessed);

        // Validate metric increments
        assertTrue(finalSent >= initialSent + messageCount, 
            String.format("Messages sent should increase by at least %d, got increase of %.0f", 
                messageCount, finalSent - initialSent));
        assertTrue(finalReceived >= initialReceived + messageCount, 
            String.format("Messages received should increase by at least %d, got increase of %.0f", 
                messageCount, finalReceived - initialReceived));
        assertTrue(finalProcessed >= initialProcessed + messageCount, 
            String.format("Messages processed should increase by at least %d, got increase of %.0f", 
                messageCount, finalProcessed - initialProcessed));

        consumer.close();
        producer.close();
    }

    private void testProcessingTimeMetricsForMode(String topicName, ConsumerMode mode, int messageCount) throws Exception {
        // Get processing time timer
        Timer processingTimer = meterRegistry.find("peegeeq.message.processing.time").timer();
        assertNotNull(processingTimer, "Message processing time timer should be registered");
        
        long initialCount = processingTimer.count();
        logger.info("📊 Initial processing timer count: {}", initialCount);

        // Create consumer with artificial processing delay
        CountDownLatch latch = new CountDownLatch(messageCount);
        
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class,
            ConsumerConfig.builder()
                .mode(mode)
                .pollingInterval(Duration.ofMillis(100))
                .build());

        consumer.subscribe(message -> {
            // Add small processing delay to ensure measurable timing
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(1000);

        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        // Send messages
        for (int i = 0; i < messageCount; i++) {
            producer.send("Processing time test message " + i);
        }

        // Wait for all messages to be processed
        assertTrue(latch.await(15, TimeUnit.SECONDS), "All messages should be processed within timeout");

        // Wait for metrics to be updated
        Thread.sleep(1000);

        // Verify processing time metrics were recorded
        long finalCount = processingTimer.count();
        double totalTime = processingTimer.totalTime(TimeUnit.MILLISECONDS);
        
        logger.info("📊 Final processing timer - Count: {}, Total time: {}ms", finalCount, totalTime);

        assertTrue(finalCount >= initialCount + messageCount, 
            String.format("Processing timer count should increase by at least %d, got increase of %d", 
                messageCount, finalCount - initialCount));
        assertTrue(totalTime > 0, "Total processing time should be greater than 0");

        consumer.close();
        producer.close();
    }
}
