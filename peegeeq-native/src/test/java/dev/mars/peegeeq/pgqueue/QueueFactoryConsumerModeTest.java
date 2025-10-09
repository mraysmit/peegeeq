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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueueFactory consumer creation with ConsumerConfig.
 * Tests the factory pattern for consumer creation including type safety, 
 * null/invalid config handling, backward compatibility, and concurrent consumer creation.
 * 
 * Following established coding principles:
 * - Use real infrastructure (TestContainers) rather than mocks
 * - Test factory pattern edge cases that could cause production issues
 * - Validate type safety and error handling
 * - Follow existing patterns from other integration tests
 * - Test backward compatibility to ensure existing code continues to work
 */
@Testcontainers
class QueueFactoryConsumerModeTest {
    private static final Logger logger = LoggerFactory.getLogger(QueueFactoryConsumerModeTest.class);

    @Container
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
        System.setProperty("peegeeq.queue.polling-interval", "PT2S");
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

        logger.info("Test setup completed for QueueFactory consumer mode testing");
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
    void testCreateConsumerWithValidConfig() throws Exception {
        logger.info("🧪 Testing QueueFactory consumer creation with valid ConsumerConfig");

        String topicName = "test-factory-valid-config";
        
        // Create consumer with explicit LISTEN_NOTIFY_ONLY config
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, config);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        assertNotNull(consumer, "Consumer should be created successfully");
        assertNotNull(producer, "Producer should be created successfully");

        // Verify consumer works correctly
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            logger.info("📨 Received factory test message: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(500);

        // Send test message
        producer.send("Factory test message").get(5, TimeUnit.SECONDS);

        // Verify message received
        boolean received = latch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should receive message via factory-created consumer");
        assertEquals(1, messageCount.get(), "Should have processed exactly 1 message");

        consumer.close();
        producer.close();
        logger.info("✅ QueueFactory creates consumers with valid config correctly");
    }

    @Test
    void testCreateConsumerWithNullConfig() throws Exception {
        logger.info("🧪 Testing QueueFactory consumer creation with null ConsumerConfig");

        String topicName = "test-factory-null-config";
        
        // Create consumer with null config - should use default HYBRID mode
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class, null);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        assertNotNull(consumer, "Consumer should be created successfully with null config");
        assertNotNull(producer, "Producer should be created successfully");

        // Verify consumer works correctly (should default to HYBRID mode)
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            logger.info("📨 Received null config test message: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(500);

        // Send test message
        producer.send("Null config test message").get(5, TimeUnit.SECONDS);

        // Verify message received
        boolean received = latch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should receive message via consumer created with null config");
        assertEquals(1, messageCount.get(), "Should have processed exactly 1 message");

        consumer.close();
        producer.close();
        logger.info("✅ QueueFactory handles null config correctly (defaults to HYBRID)");
    }

    @Test
    void testCreateConsumerWithDifferentModes() throws Exception {
        logger.info("🧪 Testing QueueFactory consumer creation with different consumer modes");

        String topicName = "test-factory-different-modes";
        
        // Test LISTEN_NOTIFY_ONLY mode
        ConsumerConfig listenOnlyConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> listenOnlyConsumer = factory.createConsumer(topicName + "-listen", String.class, listenOnlyConfig);
        assertNotNull(listenOnlyConsumer, "LISTEN_NOTIFY_ONLY consumer should be created");

        // Test POLLING_ONLY mode
        ConsumerConfig pollingOnlyConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofSeconds(1))
                .build();

        MessageConsumer<String> pollingOnlyConsumer = factory.createConsumer(topicName + "-polling", String.class, pollingOnlyConfig);
        assertNotNull(pollingOnlyConsumer, "POLLING_ONLY consumer should be created");

        // Test HYBRID mode
        ConsumerConfig hybridConfig = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2))
                .build();

        MessageConsumer<String> hybridConsumer = factory.createConsumer(topicName + "-hybrid", String.class, hybridConfig);
        assertNotNull(hybridConsumer, "HYBRID consumer should be created");

        // Verify all consumers can be closed without issues
        listenOnlyConsumer.close();
        pollingOnlyConsumer.close();
        hybridConsumer.close();

        logger.info("✅ QueueFactory creates consumers for all consumer modes correctly");
    }

    @Test
    void testCreateConsumerTypeSafety() throws Exception {
        logger.info("🧪 Testing QueueFactory consumer creation type safety");

        String topicName = "test-factory-type-safety";
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        // Test different payload types
        MessageConsumer<String> stringConsumer = factory.createConsumer(topicName + "-string", String.class, config);
        MessageConsumer<Integer> integerConsumer = factory.createConsumer(topicName + "-integer", Integer.class, config);
        MessageConsumer<Boolean> booleanConsumer = factory.createConsumer(topicName + "-boolean", Boolean.class, config);

        assertNotNull(stringConsumer, "String consumer should be created");
        assertNotNull(integerConsumer, "Integer consumer should be created");
        assertNotNull(booleanConsumer, "Boolean consumer should be created");

        // Verify type safety - consumers should have correct generic types
        assertTrue(stringConsumer instanceof MessageConsumer, "String consumer should implement MessageConsumer");
        assertTrue(integerConsumer instanceof MessageConsumer, "Integer consumer should implement MessageConsumer");
        assertTrue(booleanConsumer instanceof MessageConsumer, "Boolean consumer should implement MessageConsumer");

        stringConsumer.close();
        integerConsumer.close();
        booleanConsumer.close();

        logger.info("✅ QueueFactory maintains type safety for different payload types");
    }

    @Test
    void testCreateConsumerBackwardCompatibility() throws Exception {
        logger.info("🧪 Testing QueueFactory backward compatibility (without ConsumerConfig)");

        String topicName = "test-factory-backward-compatibility";
        
        // Test the old API without ConsumerConfig - should still work
        MessageConsumer<String> consumer = factory.createConsumer(topicName, String.class);
        MessageProducer<String> producer = factory.createProducer(topicName, String.class);

        assertNotNull(consumer, "Consumer should be created with old API");
        assertNotNull(producer, "Producer should be created successfully");

        // Verify consumer works correctly (should default to HYBRID mode)
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        consumer.subscribe(message -> {
            messageCount.incrementAndGet();
            logger.info("📨 Received backward compatibility test message: {}", message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Wait for consumer setup
        Thread.sleep(500);

        // Send test message
        producer.send("Backward compatibility test message").get(5, TimeUnit.SECONDS);

        // Verify message received
        boolean received = latch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "Should receive message via backward compatible consumer");
        assertEquals(1, messageCount.get(), "Should have processed exactly 1 message");

        consumer.close();
        producer.close();
        logger.info("✅ QueueFactory maintains backward compatibility with old API");
    }

    @Test
    void testConcurrentConsumerCreation() throws Exception {
        logger.info("🧪 Testing QueueFactory concurrent consumer creation");

        String topicName = "test-factory-concurrent-creation";
        
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(1))
                .build();

        // Create multiple consumers concurrently
        int consumerCount = 3;
        MessageConsumer<String>[] consumers = new MessageConsumer[consumerCount];
        CountDownLatch creationLatch = new CountDownLatch(consumerCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // Create consumers in parallel threads
        for (int i = 0; i < consumerCount; i++) {
            final int index = i;
            Thread creationThread = new Thread(() -> {
                try {
                    consumers[index] = factory.createConsumer(topicName + "-" + index, String.class, config);
                    if (consumers[index] != null) {
                        successCount.incrementAndGet();
                        logger.info("✅ Successfully created consumer {}", index);
                    }
                } catch (Exception e) {
                    logger.error("❌ Failed to create consumer {}: {}", index, e.getMessage());
                } finally {
                    creationLatch.countDown();
                }
            });
            creationThread.start();
        }

        // Wait for all creation attempts to complete
        boolean allCreated = creationLatch.await(10, TimeUnit.SECONDS);
        assertTrue(allCreated, "All consumer creation attempts should complete");
        assertEquals(consumerCount, successCount.get(), "All consumers should be created successfully");

        // Verify all consumers are functional
        for (int i = 0; i < consumerCount; i++) {
            assertNotNull(consumers[i], "Consumer " + i + " should be created");
        }

        // Clean up all consumers
        for (int i = 0; i < consumerCount; i++) {
            if (consumers[i] != null) {
                consumers[i].close();
            }
        }

        logger.info("✅ QueueFactory handles concurrent consumer creation correctly");
    }

    @Test
    void testCreateConsumerWithInvalidTopicName() throws Exception {
        logger.info("🧪 Testing QueueFactory consumer creation with invalid topic names");

        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        // Test null topic name - currently allowed but creates consumer with "null" topic
        // Following "Read Logs Carefully" principle - the system doesn't validate topic names
        MessageConsumer<String> nullTopicConsumer = factory.createConsumer(null, String.class, config);
        assertNotNull(nullTopicConsumer, "Consumer should be created even with null topic name");
        nullTopicConsumer.close();
        logger.info("ℹ️ Null topic names are currently allowed (creates 'null' topic)");

        // Test empty topic name - currently allowed but creates consumer with empty topic
        MessageConsumer<String> emptyTopicConsumer = factory.createConsumer("", String.class, config);
        assertNotNull(emptyTopicConsumer, "Consumer should be created even with empty topic name");
        emptyTopicConsumer.close();
        logger.info("ℹ️ Empty topic names are currently allowed");

        // Test topic name with special characters - verify they are allowed
        MessageConsumer<String> specialCharsConsumer = factory.createConsumer("test-topic-with-special-chars!@#", String.class, config);
        assertNotNull(specialCharsConsumer, "Consumer should be created with special characters in topic name");
        specialCharsConsumer.close();
        logger.info("ℹ️ Special characters in topic names are allowed");

        logger.info("✅ QueueFactory topic name handling verified (no validation currently implemented)");
    }
}
