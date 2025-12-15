package dev.mars.peegeeq.pgqueue;

import dev.mars.peegeeq.api.QueueFactoryRegistrar;
import dev.mars.peegeeq.api.messaging.MessageConsumer;
import dev.mars.peegeeq.api.messaging.MessageProducer;
import dev.mars.peegeeq.api.messaging.QueueFactory;
import dev.mars.peegeeq.db.PeeGeeQManager;
import dev.mars.peegeeq.db.config.PeeGeeQConfiguration;
import dev.mars.peegeeq.db.provider.PgDatabaseService;
import dev.mars.peegeeq.db.provider.PgQueueFactoryProvider;
import dev.mars.peegeeq.test.PostgreSQLTestConstants;
import dev.mars.peegeeq.test.categories.TestCategories;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer;
import dev.mars.peegeeq.test.schema.PeeGeeQTestSchemaInitializer.SchemaComponent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for new consumer modes functionality.
 * Tests LISTEN_NOTIFY_ONLY, POLLING_ONLY, and HYBRID modes.
 */
@Tag(TestCategories.INTEGRATION)
@Testcontainers
public class ConsumerModeIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerModeIntegrationTest.class);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgreSQLTestConstants.POSTGRES_IMAGE)
            .withDatabaseName("peegeeq_test")
            .withUsername("peegeeq_user")
            .withPassword("peegeeq_password");

    private PeeGeeQManager manager;
    private QueueFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize database schema using centralized schema initializer - use QUEUE_ALL for PeeGeeQManager health checks
        logger.info("Initializing database schema for consumer mode integration tests");
        PeeGeeQTestSchemaInitializer.initializeSchema(postgres, SchemaComponent.QUEUE_ALL);
        logger.info("Database schema initialized successfully using centralized schema initializer");

        // Configure test properties using TestContainer pattern
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

        // Initialize PeeGeeQ
        PeeGeeQConfiguration config = new PeeGeeQConfiguration("test");
        manager = new PeeGeeQManager(config, new SimpleMeterRegistry());
        manager.start();

        // Create factory using the proper pattern
        PgDatabaseService databaseService = new PgDatabaseService(manager);
        PgQueueFactoryProvider provider = new PgQueueFactoryProvider();

        // Register native factory implementation
        PgNativeFactoryRegistrar.registerWith((QueueFactoryRegistrar) provider);

        factory = provider.createFactory("native", databaseService);

        logger.info("Test setup completed");
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
    void testListenNotifyOnlyMode() throws Exception {
        System.out.println("🧪 TEST METHOD CALLED: testListenNotifyOnlyMode");
        System.err.println("🧪 TEST METHOD CALLED: testListenNotifyOnlyMode");
        logger.info("🧪 STARTING LISTEN_NOTIFY_ONLY MODE TEST");

        // Create consumer with LISTEN_NOTIFY_ONLY mode - but don't subscribe yet
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.LISTEN_NOTIFY_ONLY)
                .build();

        MessageConsumer<String> consumer = factory.createConsumer("test-listen-only-simple", String.class, config);
        MessageProducer<String> producer = factory.createProducer("test-listen-only-simple", String.class);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        System.out.println("📝 About to call consumer.subscribe()");
        consumer.subscribe(message -> {
            System.out.println("🎯 LISTEN_NOTIFY_ONLY: Message received: " + message.getPayload());
            logger.info("🎯 LISTEN_NOTIFY_ONLY: Message received: {}", message.getPayload());
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });
        System.out.println("✅ consumer.subscribe() completed");

        // Wait a bit for LISTEN setup
        System.out.println("💤 About to sleep for 1 second");
        Thread.sleep(1000);
        System.out.println("⏰ Sleep completed");

        // Send message
        System.out.println("🔔 About to send message");
        logger.info("🔔 LISTEN_NOTIFY_ONLY: Sending test message...");
        producer.send("Hello LISTEN_NOTIFY_ONLY!").get(5, TimeUnit.SECONDS);
        System.out.println("✅ Message sent successfully");
        logger.info("✅ LISTEN_NOTIFY_ONLY: Message sent");

        // Wait for message
        boolean received = latch.await(10, TimeUnit.SECONDS);
        logger.info("📊 LISTEN_NOTIFY_ONLY: Message received: {}, Content: {}", received, receivedMessage.get());

        assertTrue(received, "Message should be received via LISTEN/NOTIFY");
        assertEquals("Hello LISTEN_NOTIFY_ONLY!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("✅ LISTEN_NOTIFY_ONLY mode test completed");
    }

    @Test
    void testPollingOnlyMode() throws Exception {
        logger.info("🧪 Testing POLLING_ONLY mode");

        // Create consumer with POLLING_ONLY mode and fast polling
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.POLLING_ONLY)
                .pollingInterval(Duration.ofMillis(500)) // Fast polling for test
                .build();

        MessageConsumer<String> consumer = factory.createConsumer("test-polling-only", String.class, config);
        MessageProducer<String> producer = factory.createProducer("test-polling-only", String.class);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Send message
        producer.send("Hello POLLING_ONLY!");

        // Wait for message (should be received via polling)
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received via polling");
        assertEquals("Hello POLLING_ONLY!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("✅ POLLING_ONLY mode test passed");
    }

    @Test
    void testHybridMode() throws Exception {
        logger.info("🧪 Testing HYBRID mode (default behavior)");

        // Create consumer with HYBRID mode (should work like before)
        ConsumerConfig config = ConsumerConfig.builder()
                .mode(ConsumerMode.HYBRID)
                .pollingInterval(Duration.ofSeconds(2))
                .build();

        MessageConsumer<String> consumer = factory.createConsumer("test-hybrid", String.class, config);
        MessageProducer<String> producer = factory.createProducer("test-hybrid", String.class);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Give consumer time to set up both LISTEN and polling
        Thread.sleep(1000);

        // Send message
        producer.send("Hello HYBRID!");

        // Wait for message (should be received via LISTEN/NOTIFY or polling)
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received via HYBRID mode");
        assertEquals("Hello HYBRID!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("✅ HYBRID mode test passed");
    }

    @Test
    void testBackwardCompatibility() throws Exception {
        logger.info("🧪 Testing backward compatibility (no ConsumerConfig)");

        // Create consumer without ConsumerConfig (should default to HYBRID)
        MessageConsumer<String> consumer = factory.createConsumer("test-backward-compat", String.class);
        MessageProducer<String> producer = factory.createProducer("test-backward-compat", String.class);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedMessage = new AtomicReference<>();

        // Subscribe to messages
        consumer.subscribe(message -> {
            receivedMessage.set(message.getPayload());
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        });

        // Give consumer time to set up
        Thread.sleep(1000);

        // Send message
        producer.send("Hello Backward Compatibility!");

        // Wait for message
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Message should be received in backward compatibility mode");
        assertEquals("Hello Backward Compatibility!", receivedMessage.get());

        consumer.close();
        producer.close();
        logger.info("✅ Backward compatibility test passed");
    }
}
