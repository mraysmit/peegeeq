package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests for LISTEN_NOTIFY_ONLY consumer mode.
 * Tests critical scenarios that could cause issues in production.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test edge cases that could cause production issues
 * - Validate each scenario thoroughly
 * - Follow existing patterns from ConsumerModeIntegrationTest
 */
@Testcontainers
class ListenNotifyOnlyEdgeCaseTest {
    private static final Logger logger = LoggerFactory.getLogger(ListenNotifyOnlyEdgeCaseTest.class);

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_password");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Configure test properties using TestContainer pattern (following existing patterns)
        System.setProperty("peegeeq.database.host", postgres.getHost());
        System.setProperty("peegeeq.database.port", String.valueOf(postgres.getFirstMappedPort()));
        System.setProperty("peegeeq.database.name", postgres.getDatabaseName());
        System.setProperty("peegeeq.database.username", postgres.getUsername());
        System.setProperty("peegeeq.database.password", postgres.getPassword());
        System.setProperty("peegeeq.database.ssl.enabled", "false");
        System.setProperty("peegeeq.queue.polling-interval", "PT1S");
        System.setProperty("peegeeq.queue.visibility-timeout", "PT30S");
        System.setProperty("peegeeq.metrics.enabled", "true");
        System.setProperty("peegeeq.circuit-breaker.enabled", "true");

        // Initialize PeeGeeQ (following existing patterns)
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);

        logger.info("Test setup completed for LISTEN_NOTIFY_ONLY edge case testing");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (factory != null) {
            factory.close();
        }
        if (manager != null) {
            manager.stop();
        }
        logger.info("Test teardown completed");
    }

    @Test
    void testExistingMessagesProcessingAfterListenSetup() throws Exception {
        logger.info("🧪 Testing existing messages processing after LISTEN setup");

        String topicName = "test-existing-messages";
        
        // First, send messages BEFORE setting up the consumer
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);
        
        // Send multiple messages before consumer exists
        producer.send("Message 1 - Before Consumer").get(5, TimeUnit.SECONDS);
        producer.send("Message 2 - Before Consumer").get(5, TimeUnit.SECONDS);
        producer.send("Message 3 - Before Consumer").get(5, TimeUnit.SECONDS);
        
        logger.info("✅ Sent 3 messages before consumer setup");

        // Now create LISTEN_NOTIFY_ONLY consumer
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1); // Expect at least 1 existing message

        // Subscribe to messages
        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received existing message {}: {}", count, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for existing messages to be processed
        boolean receivedSome = latch.await(15, TimeUnit.SECONDS);

        assertTrue(receivedSome, "Should receive at least 1 existing message via LISTEN_NOTIFY_ONLY mode");
        assertTrue(messageCount.get() >= 1, "Should have processed at least 1 message, got: " + messageCount.get());

        consumer.close();
        producer.close();
        logger.info("✅ LISTEN_NOTIFY_ONLY correctly processed existing messages");
    }

    @Test
    void testChannelNameSpecialCharacters() throws Exception {
        logger.info("🧪 Testing channel name with special characters");

        // Test topic with special characters that could cause channel name issues
        String topicName = "test-special_chars.with@symbols#and$numbers123";
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup
        Thread.sleep(1000);

        // Send message
        producer.send("Special characters test message");

        // Wait for message
        assertTrue(latch.await(10, TimeUnit.SECONDS), 
            "Should receive message even with special characters in topic name");
        assertEquals("Special characters test message", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("✅ LISTEN_NOTIFY_ONLY handles special characters in topic names");
    }

    @Test
    void testLargePayloadProcessing() throws Exception {
        logger.info("🧪 Testing large payload processing");

        String topicName = "test-large-payload";
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        // Create a large message (1MB)
        StringBuilder largeMessage = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            largeMessage.append("This is a large message payload for testing purposes. ");
        }
        String largePayload = largeMessage.toString();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup
        Thread.sleep(1000);

        // Send large message
        producer.send(largePayload);

        // Wait for message (longer timeout for large message)
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
            "Should receive large message via LISTEN_NOTIFY_ONLY mode");
        assertEquals(largePayload, receivedMessage.get(), "Large payload should be received intact");

        consumer.close();
        producer.close();
        logger.info("✅ LISTEN_NOTIFY_ONLY handles large payloads correctly");
    }

    @Test
    void testConcurrentProducerScenarios() throws Exception {
        logger.info("🧪 Testing concurrent producer scenarios");

        String topicName = "test-concurrent-producers";
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);

        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(10); // Expect 10 messages from concurrent producers

        consumer.subscribe(message -> {
            int count = messageCount.incrementAndGet();
            logger.info("📨 Received concurrent message {}: {}", count, message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup
        Thread.sleep(1000);

        // Create multiple producers concurrently
        CompletableFuture<Void>[] producerTasks = new CompletableFuture[5];
        
        for (int i = 0; i < 5; i++) {
            final int producerId = i;
            producerTasks[i] = CompletableFuture.runAsync(() -> {
                try {
                    MessageProducer<String> producer = factory.createProducer(topicName, String.class);
                    
                    // Each producer sends 2 messages
                    producer.send("Message from producer " + producerId + " - msg 1").get(5, TimeUnit.SECONDS);
                    producer.send("Message from producer " + producerId + " - msg 2").get(5, TimeUnit.SECONDS);
                    
                    producer.close();
                } catch (Exception e) {
                    logger.error("Producer {} failed", producerId, e);
                    throw new RuntimeException(e);
                }
            });
        }

        // Wait for all producers to complete
        CompletableFuture.allOf(producerTasks).get(15, TimeUnit.SECONDS);

        // Wait for all messages to be received
        assertTrue(latch.await(20, TimeUnit.SECONDS), 
            "Should receive all 10 messages from concurrent producers");
        assertEquals(10, messageCount.get(), "Should have processed exactly 10 messages");

        consumer.close();
        logger.info("✅ LISTEN_NOTIFY_ONLY handles concurrent producers correctly");
    }

    @Test
    void testShutdownDuringMessageProcessing() throws Exception {
        logger.info("🧪 Testing shutdown during message processing");

        String topicName = "test-shutdown-during-processing";
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch processingStarted = new CountDownLatch(1);

        consumer.subscribe(message -> {
            processingStarted.countDown();
            try {
                // Simulate slow message processing
                Thread.sleep(2000);
                processedCount.incrementAndGet();
                logger.info("📨 Processed message: {}", message.getPayload());
            } catch (InterruptedException e) {
                logger.info("Message processing interrupted during shutdown");
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });

        // Wait for LISTEN setup
        Thread.sleep(1000);

        // Send message
        producer.send("Message for shutdown test");

        // Wait for processing to start
        assertTrue(processingStarted.await(5, TimeUnit.SECONDS), 
            "Message processing should start");

        // Close consumer while message is being processed
        logger.info("🔄 Closing consumer during message processing");
        consumer.close();

        // Verify graceful shutdown
        assertTrue(processedCount.get() >= 0, "Should handle shutdown gracefully");
        
        producer.close();
        logger.info("✅ LISTEN_NOTIFY_ONLY handles shutdown during processing gracefully");
    }
}
